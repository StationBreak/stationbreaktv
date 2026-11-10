// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.distinctByFold
import tv.stationbreak.core.model.foldNameAscii
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
        val studios: List<String> = emptyList(),
        val name: String? = null,
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
    private val failedPasses = mutableMapOf<LibraryAccount, Int>()
    private val seriesNameRows = mutableMapOf<Pair<LibraryAccount, CanonicalId>, String>()
    private val collectionNameRows = mutableMapOf<Pair<LibraryAccount, CanonicalId>, NameRow>()
    private val passes = MutableStateFlow<Map<LibraryAccount, Instant>>(emptyMap())
    private val changes = MutableStateFlow<Map<LibraryAccount, Long>>(emptyMap())
    private val pending = mutableMapOf<LibraryAccount, MutableSet<ItemId>>()

    // A library's hold and add stamp, as applySelection set them (spec 26), and its walk's proof and rewalk mark.
    private data class LibraryMark(
        val heldUntilMs: Long? = null,
        val addStamp: Long? = null,
        val walkedStamp: Long? = null,
        val rewalkPending: Boolean = false,
    )

    private val libraryMarks = mutableMapOf<LibraryAccount, MutableMap<ItemId, LibraryMark>>()
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
        noteContentChange(account)
    }

    /** Marks the rows saved with [seed] for [ids] deleted, as a sync that finds them gone does. */
    fun tombstone(
        account: LibraryAccount,
        ids: List<ItemId>,
    ) {
        val wanted = ids.toSet()
        rows.replaceAll {
            if (it.account == account && it.item.id in wanted) Row(it.account, it.library, it.item, true) else it
        }
        noteContentChange(account)
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
        noteContentChange(account)
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
            val extras = Extras(it.seasonId, it.officialRating, it.genres, it.tags, it.studios, it.name)
            held += HeldRow(account, ItemId(it.libraryId.value), it.item, extras)
        }
        noteContentChange(account)
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
        noteContentChange(account)
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
        noteContentChange(account)
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

    /** Saves the [name] of the series [id] for [account], which [programInfo] gives to the episodes of that series. */
    fun seedSeriesName(
        account: LibraryAccount,
        id: CanonicalId,
        name: String,
    ) {
        seriesNameRows[account to id] = name
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

    /** Says that a sync wrote a change to [account]'s items, series or collections, as [contentGeneration] shows. */
    fun noteContentChange(account: LibraryAccount) {
        changes.value = changes.value + (account to (changes.value[account] ?: 0L) + 1L)
    }

    /** Sets what [failedCollectionPasses] reports for [account]. */
    fun setFailedCollectionPasses(
        account: LibraryAccount,
        count: Int,
    ) {
        failedPasses[account] = count
    }

    /**
     * Says whether a full collection pass has succeeded for [account], which `RuleSnapshot.collectionsSynced` shows. A
     * change moves [contentGeneration], as the Room repository's first full pass does (#480).
     */
    fun setCollectionsSynced(
        account: LibraryAccount,
        synced: Boolean,
    ) {
        val changed = if (synced) collectionsSynced.add(account) else collectionsSynced.remove(account)
        if (changed) noteContentChange(account)
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
    ): RuleSnapshot = snapshotOf(account, libraries, cutoff, heldItems(account, libraries, cutoff))

    /**
     * [ruleSnapshot] of each of [selections] from one pass over the held items (of the union of the selections),
     * filtered per selection: the shape of the Room walk. Element i is what [ruleSnapshot] gives for `selections[i]`.
     */
    override suspend fun ruleSnapshots(
        account: LibraryAccount,
        selections: List<Set<ItemId>>,
        cutoff: Instant,
    ): List<RuleSnapshot> {
        val union = HashSet<ItemId>()
        for (selection in selections) union.addAll(selection)
        val all = heldItems(account, union, cutoff)
        return selections.map { selection ->
            snapshotOf(account, selection, cutoff, all.filter { it.library in selection })
        }
    }

    private fun snapshotOf(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        cutoff: Instant,
        entries: List<Held>,
    ): RuleSnapshot {
        val cutoffMs = cutoffMillis(cutoff)
        val items = ArrayList<RuleItem>()
        for (entry in entries) {
            val library = requireNotNull(CanonicalId.parseOrNull(entry.library.value))
            val extras = entry.extras
            items +=
                RuleItem(
                    entry.item,
                    library,
                    extras.seasonId,
                    extras.officialRating,
                    extras.genres,
                    extras.tags,
                    extras.studios,
                    extras.name,
                )
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

    /**
     * What [scheduleItems] gives at the cut-off over every library of [account], narrowed to [ids]. The fake keeps no
     * history, so the fields are those seeded.
     */
    override suspend fun scheduleItemsOf(
        account: LibraryAccount,
        ids: Set<CanonicalId>,
        cutoffMs: Long,
    ): List<ScheduleItem> {
        if (ids.isEmpty()) return emptyList()
        val libraries = HashSet<ItemId>()
        held.filter { it.account == account }.mapTo(libraries) { it.library }
        rows.filter { it.account == account }.mapTo(libraries) { it.library }
        return heldItems(account, libraries, Instant.fromEpochMilliseconds(cutoffMs))
            .map { it.item }
            .filter { it.id in ids }
            .distinctBy { it.id }
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

    private fun MediaItem.extras(): Extras {
        val season = seasonId?.let { CanonicalId.parseOrNull(it.value) }
        return Extras(season, officialRating, genres, tags, studios, name)
    }

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

    override suspend fun distinctGenres(account: LibraryAccount): List<String> = distinct(account, { it.genres }, { it.genres })

    override suspend fun distinctTags(account: LibraryAccount): List<String> = distinct(account, { it.tags }, { it.tags })

    override suspend fun distinctStudios(account: LibraryAccount): List<String> = distinct(account, { it.studios }, { it.studios })

    // The live items' and series' names, one per folded name, as the real one.
    private fun distinct(
        account: LibraryAccount,
        ofItem: (MediaItem) -> List<String>,
        ofSeries: (RuleSeries) -> List<String>,
    ): List<String> {
        val fromItems = rows.filter { it.account == account && !it.tombstoned }.flatMap { ofItem(it.item) }
        val fromSeries = seriesRows.filter { it.account == account && it.deletedAtMs == null }.flatMap { ofSeries(it.series) }
        return (fromItems + fromSeries).let(::distinctByFold)
    }

    /** Finds the live movies saved with [seed] and the series named with [seedSeriesName], as the real one does. */
    override suspend fun searchTitles(
        account: LibraryAccount,
        text: String,
        limit: Int,
    ): List<TitleHit> {
        if (limit <= 0 || text.isEmpty()) return emptyList()
        val wanted = foldNameAscii(text)
        val films =
            rows
                .filter { it.account == account && !it.tombstoned && it.item.kind == ItemKind.Movie }
                .mapNotNull { row ->
                    val id = CanonicalId.parseOrNull(row.item.id.value)
                    id
                        ?.takeIf { wanted in foldNameAscii(row.item.name) }
                        ?.let { TitleHit(it, row.item.name, TitleKind.Film, row.item.productionYear) }
                }
        val shows =
            seriesNameRows
                .filter { (key, name) -> key.first == account && wanted in foldNameAscii(name) }
                .map { (key, name) -> TitleHit(key.second, name, TitleKind.Show, null) }
        return (films + shows).sortedWith(compareBy({ it.name }, { it.id.value })).take(limit)
    }

    override suspend fun titleNames(
        account: LibraryAccount,
        ids: Set<CanonicalId>,
    ): Map<CanonicalId, String> {
        val series = seriesNameRows.filter { (key, _) -> key.first == account && key.second in ids }
        val films =
            rows
                .filter { it.account == account && !it.tombstoned && it.item.kind == ItemKind.Movie }
                .mapNotNull { row -> CanonicalId.parseOrNull(row.item.id.value)?.takeIf { it in ids }?.let { it to row.item.name } }
        return series.entries.associate { it.key.second to it.value } + films
    }

    /** Reads the live rows saved with [seed]; the series names come from [seedSeriesName]. */
    override suspend fun programInfo(
        account: LibraryAccount,
        ids: Set<CanonicalId>,
    ): Map<CanonicalId, ProgramInfo> =
        rows
            .filter { it.account == account && !it.tombstoned }
            .mapNotNull { row ->
                val id = CanonicalId.parseOrNull(row.item.id.value)?.takeIf { it in ids } ?: return@mapNotNull null
                val kind = ScheduleItemKind.entries.firstOrNull { it.name == row.item.kind.name } ?: return@mapNotNull null
                val series = row.item.seriesId?.let { CanonicalId.parseOrNull(it.value) }
                val seriesName = series?.let { seriesNameRows[account to it] }
                id to
                    ProgramInfo(
                        id,
                        kind,
                        row.item.name,
                        seriesName,
                        row.item.seasonNumber,
                        row.item.episodeNumber,
                        row.item.productionYear,
                    )
            }.toMap()

    override suspend fun currentIds(
        account: LibraryAccount,
        ids: Set<CanonicalId>,
    ): Set<CanonicalId> =
        rows
            .filter { it.account == account && !it.tombstoned }
            .mapNotNullTo(HashSet()) { row -> CanonicalId.parseOrNull(row.item.id.value)?.takeIf { it in ids } }

    override fun collectionPassCompleted(account: LibraryAccount): Flow<Instant?> = passes.map { it[account] }

    override suspend fun contentGeneration(account: LibraryAccount): Long = changes.value[account] ?: 0L

    override suspend fun failedCollectionPasses(account: LibraryAccount): Int = failedPasses[account] ?: 0

    @Suppress("OVERRIDE_DEPRECATION")
    override suspend fun excludedCount(
        account: LibraryAccount,
        libraries: Set<ItemId>,
    ): Int = live(account, libraries).count { !it.item.runtime.usable() }

    // The contract of applySelection, in memory: a library is stored when it has rows, is pending, or has a hold or a
    // stamp recorded here. The generation moves only when a library goes that had no stamp, or that held rows.
    override suspend fun applySelection(
        account: LibraryAccount,
        keep: Set<ItemId>,
        addStamps: Map<ItemId, Long>,
        holds: Map<ItemId, Long>,
        nowMs: Long,
    ) {
        val marks = libraryMarks.getOrPut(account) { mutableMapOf() }
        val waiting = pending.getOrPut(account) { mutableSetOf() }
        val stored =
            rows.filter { it.account == account }.map { it.library }.toSet() +
                held.filter { it.account == account }.map { it.library } + waiting + marks.keys
        // As the port says: a new stamp only for a library that is new, held or already being added.
        for ((library, stamp) in addStamps) {
            val mark = marks[library]
            val landed = library in stored && mark?.heldUntilMs == null && mark?.addStamp == null
            require(library !in keep || stamp == mark?.addStamp || !landed) {
                "A new add stamp goes only to a library that is new, held or being added"
            }
        }
        val staying = keep.toMutableSet()
        var dropped = false
        for (library in stored - keep) {
            val mark = marks[library]
            // As Room: a hold for a library already held keeps the later of the two.
            val until = listOfNotNull(holds[library], mark?.heldUntilMs).maxOrNull()
            if (until != null && until > nowMs) {
                // A held library is never one being added: its stamp, its proof and its rewalk mark go.
                marks[library] = LibraryMark(heldUntilMs = until)
                staying += library
            } else {
                val hasRows =
                    rows.any { it.account == account && it.library == library } ||
                        held.any { it.account == account && it.library == library }
                // As Room: only a library that holds rows moves the generation when it goes.
                if (hasRows) dropped = true
                marks.remove(library)
            }
        }
        rows.removeAll { it.account == account && it.library !in staying }
        held.removeAll { it.account == account && it.library !in staying }
        waiting.retainAll(staying)
        for (library in keep) {
            val mark = marks[library] ?: LibraryMark()
            val stamp = addStamps[library]
            val newStamp = stamp != null && stamp != mark.addStamp
            // As Room: a library not stored before is new, so pending; one that was fetched stays fetched.
            if (newStamp || library !in stored) waiting += library
            // A new stamp clears the proof of an earlier walk, and the rewalk mark; anything else keeps both.
            marks[library] =
                if (newStamp) {
                    LibraryMark(addStamp = stamp)
                } else {
                    mark.copy(heldUntilMs = null)
                }
        }
        if (dropped) noteContentChange(account)
    }

    // As the port says: only the stamp the add has, with its walk's proof (and no hold), lands it, and then the
    // generation moves once. As in Room, an account that is not the signed-in one lands nothing.
    override suspend fun landAdd(
        account: LibraryAccount,
        library: ItemId,
        stamp: Long,
    ): Boolean {
        if (active.value != account) return false
        val marks = libraryMarks[account] ?: return false
        val mark = marks[library]
        if (mark?.addStamp != stamp || mark.walkedStamp != stamp || mark.heldUntilMs != null) return false
        marks[library] = mark.copy(addStamp = null, walkedStamp = null, rewalkPending = false)
        pending[account]?.remove(library)
        noteContentChange(account)
        return true
    }

    /**
     * Records that the add walk of [library] under [stamp] ended, as Room's `markAddWalked` does in the walk's last
     * transaction: only while the library has that stamp and no hold. False (nothing recorded) otherwise.
     */
    fun proveWalk(
        account: LibraryAccount,
        library: ItemId,
        stamp: Long,
    ): Boolean {
        val marks = libraryMarks[account] ?: return false
        val mark = marks[library]
        if (mark?.addStamp != stamp || mark.heldUntilMs != null) return false
        marks[library] = mark.copy(walkedStamp = stamp)
        return true
    }

    // As Room's compare-and-set: the stamp it has, no proof for it and no hold, for the signed-in account only. The
    // generation moves once when the library holds rows, which are a chosen library's from now on.
    override suspend fun revertUnproven(
        account: LibraryAccount,
        library: ItemId,
        stamp: Long,
    ): Boolean {
        if (active.value != account) return false
        val marks = libraryMarks[account] ?: return false
        val mark = marks[library]
        if (mark?.addStamp != stamp || mark.walkedStamp == stamp || mark.heldUntilMs != null) return false
        marks[library] = mark.copy(addStamp = null, walkedStamp = null, rewalkPending = true)
        pending.getOrPut(account) { mutableSetOf() } += library
        val hasRows =
            rows.any { it.account == account && it.library == library } ||
                held.any { it.account == account && it.library == library }
        if (hasRows) noteContentChange(account)
        return true
    }

    override suspend fun pendingLibraries(account: LibraryAccount): Set<ItemId> = pending[account].orEmpty().toSet()

    override suspend fun storedLibraries(account: LibraryAccount): List<StoredLibrary> {
        val waiting = pending[account].orEmpty()
        return libraryMarks[account]
            .orEmpty()
            .map { (id, mark) ->
                StoredLibrary(id, id !in waiting, mark.addStamp, mark.walkedStamp, mark.heldUntilMs, mark.rewalkPending)
            }.sortedBy { it.id.value }
    }

    private val successorRows = mutableMapOf<Pair<LibraryAccount, CanonicalId>, CanonicalId>()

    /**
     * Says the item [gone] came back as [successor], for [successors]. The fake does no matching of its own: the
     * conservative one is `data:library`'s. It answers only for a successor that is live in a library asked for.
     */
    fun seedSuccessor(
        account: LibraryAccount,
        gone: CanonicalId,
        successor: CanonicalId,
    ) {
        successorRows[account to gone] = successor
    }

    override suspend fun successors(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        gone: Set<CanonicalId>,
    ): Map<CanonicalId, CanonicalId> {
        val liveIds = live(account, libraries).mapNotNullTo(HashSet()) { CanonicalId.parseOrNull(it.item.id.value) }
        val found = LinkedHashMap<CanonicalId, CanonicalId>()
        val rowIds = rows.filter { it.account == account }.groupBy { CanonicalId.parseOrNull(it.item.id.value) }
        for (id in gone) {
            // Only a tombstone is gone: an id with no row, or with a live one, is absent, as the real read has it.
            val own = rowIds[id].orEmpty()
            if (own.isEmpty() || own.any { !it.tombstoned }) continue
            val successor = successorRows[account to id] ?: continue
            if (successor in liveIds) found[id] = successor
        }
        return found
    }

    override suspend fun useAccount(account: LibraryAccount) {
        activated += account
        active.value = account
        rows.removeAll { it.account != account }
        held.removeAll { it.account != account }
        clearRuleInputs { it != account }
        pending.keys.retainAll(setOf(account))
        libraryMarks.keys.retainAll(setOf(account))
        statuses.value = statuses.value.filterKeys { it == account }
    }

    override suspend fun forget(account: LibraryAccount) {
        forgotten += account
        check(!failForget) { "the cache could not be cleared" }
        rows.removeAll { it.account == account }
        held.removeAll { it.account == account }
        clearRuleInputs { it == account }
        pending.remove(account)
        libraryMarks.remove(account)
        statuses.value = statuses.value - account
        if (active.value == account) active.value = null
    }

    private fun clearRuleInputs(forAccount: (LibraryAccount) -> Boolean) {
        seriesRows.removeAll { forAccount(it.account) }
        collectionRows.removeAll { forAccount(it.account) }
        collectionsSynced.removeAll(forAccount)
        collectionNameRows.keys.removeAll { forAccount(it.first) }
        seriesNameRows.keys.removeAll { forAccount(it.first) }
        successorRows.keys.removeAll { forAccount(it.first) }
        passes.value = passes.value.filterKeys { !forAccount(it) }
    }

    private fun live(
        account: LibraryAccount,
        libraries: Set<ItemId>,
    ) = rows.filter { it.account == account && it.library in libraries && !it.tombstoned }

    private fun Duration?.usable() = this != null && this > Duration.ZERO
}
