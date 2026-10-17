// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaItem
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
    )

    private val rows = mutableListOf<Row>()
    private val held = mutableListOf<HeldRow>()
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

    /** Sets what [status] reports for [account]. */
    fun setStatus(
        account: LibraryAccount,
        status: LibraryStatus,
    ) {
        statuses.value = statuses.value + (account to status)
    }

    override fun status(account: LibraryAccount): Flow<LibraryStatus> =
        statuses.map { it[account] ?: LibraryStatus(SyncState.NeverSynced, null) }

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
    ): List<ScheduleItem> {
        val floor = cutoff.toEpochMilliseconds()
        val cutoffMs = if (Instant.fromEpochMilliseconds(floor) < cutoff) floor + 1 else floor
        val fromSync = live(account, libraries).mapNotNull { it.item.toScheduleItemOrNull() }
        return (held.filter { it.account == account && it.library in libraries }.map { it.item } + fromSync)
            .filter { (it.deletedAtMs ?: Long.MAX_VALUE) >= cutoffMs }
            .sortedBy { it.id.value }
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
        pending.keys.retainAll(setOf(account))
        statuses.value = statuses.value.filterKeys { it == account }
    }

    override suspend fun forget(account: LibraryAccount) {
        forgotten += account
        check(!failForget) { "the cache could not be cleared" }
        rows.removeAll { it.account == account }
        held.removeAll { it.account == account }
        pending.remove(account)
        statuses.value = statuses.value - account
        if (active.value == account) active.value = null
    }

    private fun live(
        account: LibraryAccount,
        libraries: Set<ItemId>,
    ) = rows.filter { it.account == account && it.library in libraries && !it.tombstoned }

    private fun Duration?.usable() = this != null && this > Duration.ZERO
}
