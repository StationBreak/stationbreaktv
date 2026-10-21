// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A [LibraryRepository] in memory for tests (ADR-0004). It follows the port's contract (snapshot filtering, the
 * exclusion count, account isolation, pruning on selection) and records the [AccountCache] calls it got, so a test of
 * the sign-in path can prove who was activated and who was forgotten. It is not a database: the real one is
 * `data:library`'s.
 */
class InMemoryLibraryRepository : LibraryRepository {
    private class Row(
        val account: LibraryAccount,
        val library: ItemId,
        val item: MediaItem,
        val tombstoned: Boolean,
    )

    private class HeldRow(
        val account: LibraryAccount,
        val library: ItemId,
        val item: ScheduleItem,
        val extras: Extras = Extras(),
    )

    // What the rules read beside a ScheduleItem; the fake keeps no history of it either.
    private data class Extras(
        val seasonId: CanonicalId? = null,
        val officialRating: String? = null,
        val genres: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
    )

    // One item the as-of read returns, with the library it was held in.
    private class Held(
        val library: ItemId,
        val item: ScheduleItem,
        val extras: Extras,
    )

    private class SeriesRow(
        val account: LibraryAccount,
        val series: RuleSeries,
        val deletedAtMs: Long?,
    )

    private class CollectionRow(
        val account: LibraryAccount,
        val collection: RuleCollection,
        val deletedAtMs: Long?,
    )

    private class NameRow(
        val account: LibraryAccount,
        var name: String,
        var deleted: Boolean,
    )

    private val rows = mutableListOf<Row>()
    private val held = mutableListOf<HeldRow>()
    private val seriesRows = mutableListOf<SeriesRow>()
    private val collectionRows = mutableListOf<CollectionRow>()
    private val collectionsSynced = mutableSetOf<LibraryAccount>()
    private val collectionNameRows = mutableMapOf<Pair<LibraryAccount, CanonicalId>, NameRow>()
    private val passes = MutableStateFlow<Map<LibraryAccount, Instant>>(emptyMap())
    private val changes = MutableStateFlow<Map<LibraryAccount, Long>>(emptyMap())
    private val pending = mutableMapOf<LibraryAccount, MutableSet<ItemId>>()
    private val statuses = MutableStateFlow<Map<LibraryAccount, LibraryStatus>>(emptyMap())
    private val active = MutableStateFlow<LibraryAccount?>(null)

    /** Every account passed to [useAccount], in order. */
    val activated = mutableListOf<LibraryAccount>()

    /** Every account passed to [forget], in order. */
    val forgotten = mutableListOf<LibraryAccount>()

    /** Makes [forget] throw, to prove a failing cache cannot stop a sign-out. */
    var failForget = false

    /** Saves [items] for [account] under [library]; with [tombstoned] they read as deleted. */
    fun seed(
        account: LibraryAccount,
        library: ItemId,
        vararg items: MediaItem,
        tombstoned: Boolean = false,
    ) {
        items.forEach { rows += Row(account, library, it, tombstoned) }
    }

    /**
     * Saves [items] as the versions [scheduleItems] reads for [account] under [library]. The fake keeps no history:
     * what is seeded is what every cut-off sees, except that an item with a `deletedAtMs` before the cut-off is left
     * out. Rows saved with [seed] are read too (see [scheduleItems]).
     */
    fun seedSchedule(
        account: LibraryAccount,
        library: ItemId,
        vararg items: ScheduleItem,
    ) {
        items.forEach { held += HeldRow(account, library, it) }
    }

    /**
     * Saves [items] as the versions [ruleSnapshot] and [scheduleItems] read for [account], each in the library its
     * `libraryId` names. As for [seedSchedule], the fake keeps no history.
     */
    fun seedRule(
        account: LibraryAccount,
        vararg items: RuleItem,
    ) {
        items.forEach {
            val extras = Extras(it.seasonId, it.officialRating, it.genres, it.tags)
            held += HeldRow(account, ItemId(it.libraryId.value), it.item, extras)
        }
    }

    /**
     * Saves [series] for [account]. [ruleSnapshot] returns it only when a returned episode points to it, and a series
     * with a [deletedAtMs] is returned until `deletedAtMs < cutoff`.
     */
    fun seedSeries(
        account: LibraryAccount,
        vararg series: RuleSeries,
        deletedAtMs: Long? = null,
    ) {
        series.forEach { seriesRows += SeriesRow(account, it, deletedAtMs) }
    }

    /**
     * Saves [collections] for [account], whatever the libraries are. With a [deletedAtMs] they are returned until
     * `deletedAtMs < cutoff`.
     */
    fun seedCollection(
        account: LibraryAccount,
        vararg collections: RuleCollection,
        deletedAtMs: Long? = null,
    ) {
        collections.forEach { collectionRows += CollectionRow(account, it, deletedAtMs) }
    }

    /**
     * Saves the current [name] of the collection [id] for [account], or renames it if it is saved; with [deleted] it
     * reads as deleted. [collectionNames] returns the names not deleted.
     */
    fun seedCollectionName(
        account: LibraryAccount,
        id: CanonicalId,
        name: String,
        deleted: Boolean = false,
    ) {
        collectionNameRows.getOrPut(account to id) { NameRow(account, name, deleted) }.also {
            it.name = name
            it.deleted = deleted
        }
    }

    /**
     * Says that a whole collection pass of [account] that began at [startedAt] completed, as [collectionPassCompleted]
     * shows.
     */
    fun completeCollectionPass(
        account: LibraryAccount,
        startedAt: Instant,
    ) {
        passes.value = passes.value + (account to startedAt)
    }

    /** Says that a sync wrote a change to [account]'s items, series or collections, as [contentChanges] shows. */
    fun noteContentChange(account: LibraryAccount) {
        changes.value = changes.value + (account to (changes.value[account] ?: 0L) + 1L)
    }

    /** Says whether a full collection pass has succeeded for [account], which `RuleSnapshot.collectionsSynced` shows. */
    fun setCollectionsSynced(
        account: LibraryAccount,
        synced: Boolean,
    ) {
        if (synced) collectionsSynced += account else collectionsSynced -= account
    }

    /** Sets what [status] reports for [account]. */
    fun setStatus(
        account: LibraryAccount,
        status: LibraryStatus,
    ) {
        statuses.value = statuses.value + (account to status)
    }

    override fun status(account: LibraryAccount): Flow<LibraryStatus> =
        statuses.map { it[account] ?: LibraryStatus(SyncState.NeverSynced, null) }

    override fun activeAccount(): Flow<LibraryAccount?> = active

    override fun activeStatus(): Flow<LibraryStatus?> =
        combine(active, statuses) { account, all ->
            account?.let { all[it] ?: LibraryStatus(SyncState.NeverSynced, null) }
        }

    @Suppress("OVERRIDE_DEPRECATION")
    override suspend fun snapshot(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        createdBefore: Instant,
    ): List<MediaItem> =
        live(account, libraries)
            .map { it.item }
            .filter { (it.dateCreated ?: return@filter false) < createdBefore && it.runtime.usable() }
            .sortedBy { it.id.value }

    /**
     * Reads what [seedSchedule] saved, and the live rows saved with [seed], turned into schedule items when they are
     * valid (a movie or an episode with a canonical ID, a positive runtime, a creation time and fields `ScheduleItem`
     * accepts; anything else is left out). A tombstoned [seed] row is left out too, because the fake keeps no deletion
     * time for it. It keeps no history, so every cut-off sees the same fields.
     */
    override suspend fun scheduleItems(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        cutoff: Instant,
    ): List<ScheduleItem> = heldItems(account, libraries, cutoff).map { it.item }

    /**
     * What [scheduleItems] returns with the fields the rules read, built from [seedRule] and [seedSchedule], the
     * saved series and collections, and the live rows saved with [seed]. The series are those a returned episode
     * points to, a deleted series or collection is kept until `deletedAt < cutoff`, and the collections are returned
     * whatever the [libraries] are. A library that is not a canonical ID is left out, with its items.
     */
    override suspend fun ruleSnapshot(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        cutoff: Instant,
    ): RuleSnapshot {
        val cutoffMs = cutoffMillis(cutoff)
        val items = ArrayList<RuleItem>()
        for (entry in heldItems(account, libraries, cutoff)) {
            val library = requireNotNull(CanonicalId.parseOrNull(entry.library.value))
            val extras = entry.extras
            items += RuleItem(entry.item, library, extras.seasonId, extras.officialRating, extras.genres, extras.tags)
        }
        val wanted = items.mapNotNullTo(HashSet()) { it.item.seriesId }
        val series =
            seriesRows
                .filter { it.account == account && it.series.id in wanted && it.keptAt(cutoffMs) }
                .sortedBy { it.series.id.value }
                .associate { it.series.id to it.series }
        val collections =
            collectionRows
                .filter { it.account == account && (it.deletedAtMs ?: Long.MAX_VALUE) >= cutoffMs }
                .sortedBy { it.collection.id.value }
                .associate { it.collection.id to it.collection }
        return RuleSnapshot(
            cutoffMs = cutoffMs,
            libraries = libraries.mapNotNullTo(HashSet()) { CanonicalId.parseOrNull(it.value) },
            items = items,
            series = series,
            collections = collections,
            collectionsSynced = account in collectionsSynced,
        )
    }

    private fun SeriesRow.keptAt(cutoffMs: Long) = (deletedAtMs ?: Long.MAX_VALUE) >= cutoffMs

    // The first millisecond at or after the cut-off, as the Room repository reads it.
    private fun cutoffMillis(cutoff: Instant): Long {
        val floor = cutoff.toEpochMilliseconds()
        return if (Instant.fromEpochMilliseconds(floor) < cutoff) floor + 1 else floor
    }

    private fun heldItems(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        cutoff: Instant,
    ): List<Held> {
        val cutoffMs = cutoffMillis(cutoff)
        val fromSync =
            live(account, libraries).mapNotNull { row ->
                row.item.toScheduleItemOrNull()?.let { Held(row.library, it, row.item.extras()) }
            }
        val seeded = held.filter { it.account == account && it.library in libraries }.map { Held(it.library, it.item, it.extras) }
        return (seeded + fromSync)
            .filter { CanonicalId.parseOrNull(it.library.value) != null }
            .filter { (it.item.deletedAtMs ?: Long.MAX_VALUE) >= cutoffMs }
            .sortedBy { it.item.id.value }
    }

    private fun MediaItem.extras() = Extras(seasonId?.let { CanonicalId.parseOrNull(it.value) }, officialRating, genres, tags)

    private fun MediaItem.toScheduleItemOrNull(): ScheduleItem? {
        val scheduleKind = ScheduleItemKind.entries.firstOrNull { it.name == kind.name }
        val itemId = CanonicalId.parseOrNull(id.value)
        val series = seriesId?.let { CanonicalId.parseOrNull(it.value) }
        val runtimeMs = runtime?.inWholeMilliseconds
        val created = dateCreated?.toEpochMilliseconds()
        val usable =
            scheduleKind != null && itemId != null && (seriesId == null || series != null) &&
                runtimeMs != null && runtimeMs > 0 && created != null
        val problem =
            scheduleKind?.let {
                ScheduleItem.problem(it, premiereDate, productionYear, series, seasonNumber, episodeNumber)
            }
        return if (usable && problem == null) {
            ScheduleItem(
                requireNotNull(itemId),
                requireNotNull(scheduleKind),
                requireNotNull(runtimeMs),
                requireNotNull(created),
                null,
                premiereDate,
                productionYear,
                series,
                seasonNumber,
                episodeNumber,
            )
        } else {
            null
        }
    }

    override suspend fun collectionNames(account: LibraryAccount): CollectionNames =
        CollectionNames(
            collectionNameRows
                .filter { (key, row) -> key.first == account && !row.deleted }
                .entries
                .associate { it.key.second to it.value.name },
        )

    override fun collectionPassCompleted(account: LibraryAccount): Flow<Instant?> = passes.map { it[account] }

    override fun contentChanges(account: LibraryAccount): Flow<Long> = changes.map { it[account] ?: 0L }.distinctUntilChanged()

    @Suppress("OVERRIDE_DEPRECATION")
    override suspend fun excludedCount(
        account: LibraryAccount,
        libraries: Set<ItemId>,
    ): Int = live(account, libraries).count { !it.item.runtime.usable() }

    override suspend fun select(
        account: LibraryAccount,
        libraries: Set<ItemId>,
    ) {
        rows.removeAll { it.account == account && it.library !in libraries }
        held.removeAll { it.account == account && it.library !in libraries }
        val saved = rows.filter { it.account == account }.map { it.library }.toSet()
        val held = pending.getOrPut(account) { mutableSetOf() }
        held.retainAll(libraries)
        held += libraries - saved
    }

    override suspend fun pendingLibraries(account: LibraryAccount): Set<ItemId> = pending[account].orEmpty().toSet()

    override suspend fun useAccount(account: LibraryAccount) {
        activated += account
        active.value = account
        rows.removeAll { it.account != account }
        held.removeAll { it.account != account }
        clearRuleInputs { it != account }
        pending.keys.retainAll(setOf(account))
        statuses.value = statuses.value.filterKeys { it == account }
    }

    override suspend fun forget(account: LibraryAccount) {
        forgotten += account
        check(!failForget) { "the cache could not be cleared" }
        rows.removeAll { it.account == account }
        held.removeAll { it.account == account }
        clearRuleInputs { it == account }
        pending.remove(account)
        statuses.value = statuses.value - account
        if (active.value == account) active.value = null
    }

    private fun clearRuleInputs(forAccount: (LibraryAccount) -> Boolean) {
        seriesRows.removeAll { forAccount(it.account) }
        collectionRows.removeAll { forAccount(it.account) }
        collectionsSynced.removeAll(forAccount)
        collectionNameRows.keys.removeAll { forAccount(it.first) }
        passes.value = passes.value.filterKeys { !forAccount(it) }
    }

    private fun live(
        account: LibraryAccount,
        libraries: Set<ItemId>,
    ) = rows.filter { it.account == account && it.library in libraries && !it.tombstoned }

    private fun Duration?.usable() = this != null && this > Duration.ZERO
}
