// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemIdPage
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.Session
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** What a pass did with the items it was given: new rows, rows that differed, and rows that were already identical. */
internal data class PassSummary(
    val inserted: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
) {
    operator fun plus(write: PageWrite): PassSummary {
        val added = inserted + write.inserted
        return PassSummary(added, updated + write.updated, unchanged + write.unchanged)
    }
}

/**
 * What a reconciliation did: items tombstoned, tombstones cleared, and items the cache lacked that were fetched; and,
 * for the series pass, series tombstoned and revived.
 */
internal data class ReconcileSummary(
    val tombstoned: Int = 0,
    val revived: Int = 0,
    val fetched: Int = 0,
    val seriesTombstoned: Int = 0,
    val seriesRevived: Int = 0,
) {
    operator fun plus(other: ReconcileSummary) =
        ReconcileSummary(
            tombstoned + other.tombstoned,
            revived + other.revived,
            fetched + other.fetched,
            seriesTombstoned + other.seriesTombstoned,
            seriesRevived + other.seriesRevived,
        )
}

/** How a pass ended and what it did. Returned, not kept in the engine, so nothing mutable is shared. */
internal data class StayResult<S>(
    val outcome: SyncOutcome,
    val summary: S,
)

/**
 * The IDs a library lists but a walk of its item pages could not find (they would not decode, or are listed and never
 * returned), remembered in this process only. While a library's ID list is exactly as it was when they were given
 * up on, they are not looked for again, so one open never costs a full walk twice. A changed list forgets them. It is
 * not persisted: a new process tries once more.
 */
internal class UnfindableIds {
    private class Remembered(
        val fingerprint: Int,
        val ids: Set<ItemId>,
    )

    private val byLibrary = ConcurrentHashMap<Pair<LibraryAccount, ItemId>, Remembered>()

    /** The IDs to leave out of the next walk of [library], given the [fingerprint] of the list it has now. */
    fun skip(
        account: LibraryAccount,
        library: ItemId,
        fingerprint: Int,
    ): Set<ItemId> = byLibrary[account to library]?.takeIf { it.fingerprint == fingerprint }?.ids.orEmpty()

    /** Gives up on [ids] for as long as the list keeps [fingerprint]; an empty set forgets the library. */
    fun remember(
        account: LibraryAccount,
        library: ItemId,
        fingerprint: Int,
        ids: Set<ItemId>,
    ) {
        val key = account to library
        if (ids.isEmpty()) byLibrary.remove(key) else byLibrary[key] = Remembered(fingerprint, ids)
    }
}

/** The complete ID list of one library: [trusted] is false when the library changed while it was being read. */
private class IdList(
    val ids: Set<ItemId>,
    val trusted: Boolean,
) {
    // A cheap summary of the list: the same IDs give the same number, in any order.
    val fingerprint: Int get() = 31 * ids.size + ids.hashCode()
}

/** How reading one library's IDs ended. */
private sealed interface IdsRead {
    class Complete(
        val list: IdList,
    ) : IdsRead

    class Failed(
        val error: MediaServerError,
    ) : IdsRead
}

/** One page of an ID listing, from a start index and a count: the item listing or the series listing. */
private typealias IdLister = suspend (start: Int, count: Int) -> ServerResult<ItemIdPage>

/** What one complete, guarded ID read of items or series decided: what is [gone], and what is [returning]. */
private sealed interface Decision {
    class Ready(
        val list: IdList,
        val gone: Set<ItemId>,
        val returning: Set<ItemId>,
    ) : Decision

    /** The read failed or was suspect: [outcome] is what the pass ends as, and nothing is to be written for it. */
    class Stop(
        val outcome: SyncOutcome,
    ) : Decision
}

/**
 * Keeps the saved library current after the first sync (spec 06, Unit 4): the incremental pass picks up what changed on
 * the server, quietly. Nothing here shows a screen or a message; the outcome is state in the saved library only.
 *
 * The incremental pass asks for the items saved since the last pass **began**, less [margin]. Jellyfin never sends a
 * per-item save time, so the moment a pass records is its own start on our clock, taken before the first request and
 * recorded only if the pass succeeds: whatever the server saved while the pass ran is fetched next time, and a pass
 * that dies leaves the window as it was. If the clock went back (the last success is ahead of now) the window counts
 * from now, and now is what is recorded. Pages are written with the same idempotent upsert the first sync uses, so an
 * item that comes back unchanged is not rewritten. Nothing here takes a token apart: the [Session] is only handed to
 * the [MediaServer].
 */
@Suppress("LongParameterList") // The same knobs the first-sync engine has, plus what a walk cannot find.
internal class StayCurrentEngine(
    private val repository: RoomLibraryRepository,
    private val server: MediaServer,
    private val clock: Clock,
    private val pageSize: Int = MediaServer.MAX_PAGE_SIZE,
    private val margin: Duration = FirstSyncEngine.DEFAULT_MARGIN,
    private val maxSkippedItems: Int = FirstSyncEngine.MAX_SKIPPED_ITEMS,
    private val unfindable: UnfindableIds = UnfindableIds(),
) {
    init {
        require(pageSize in 1..MediaServer.MAX_PAGE_SIZE) { "pageSize must be 1 to ${MediaServer.MAX_PAGE_SIZE}" }
    }

    /**
     * Fetches what changed in [chosen] since the last successful pass (less the margin) for [account] and saves it.
     * Ends as [SyncOutcome.Cancelled] when there is nothing to top up (the first sync has not finished) or the account
     * stopped being the signed-in one.
     */
    suspend fun incremental(
        session: Session,
        account: LibraryAccount,
        chosen: Set<ItemId>,
    ): StayResult<PassSummary> =
        try {
            val last = repository.lastSuccess(account)
            if (last == null) {
                StayResult(SyncOutcome.Cancelled, PassSummary())
            } else {
                Pass(session, account).incremental(chosen, last, clock.now())
            }
        } catch (_: AccountNotActiveException) {
            StayResult(SyncOutcome.Cancelled, PassSummary())
        }

    /**
     * Lists every item ID of each ticked library and brings the saved library in line with it (owner answer 1: on every
     * open). An ID the server lacks gets a tombstone, a tombstoned ID that is back is revived, and an ID the account
     * has no row for (an item that shifted across a page boundary while the first walk ran, and so was never asked
     * for) is fetched. A library is acted on only after its **complete** list was read: a failed, cut-short, stuck or
     * endless list changes nothing, an empty list for a library that had items is suspect and rejected, and a list
     * whose size or count does not match what the server said tombstones nothing. Even a list that looks sound is
     * read a second time before anything is tombstoned, and only what both reads lack is. The cache is read before
     * the server, so what another pass saves meanwhile cannot look deleted. It never moves the recorded moment.
     */
    suspend fun reconcile(
        session: Session,
        account: LibraryAccount,
        chosen: Set<ItemId>,
    ): StayResult<ReconcileSummary> =
        try {
            Pass(session, account).reconcile(chosen)
        } catch (_: AccountNotActiveException) {
            StayResult(SyncOutcome.Cancelled, ReconcileSummary())
        }

    /**
     * Brings the saved collections in line with the server's (spec 10, Unit 2): see [CollectionPass]. It runs after the
     * item passes. A failure is returned and recorded nowhere in the account's sync state, so it never shows as a
     * failed library. An account that stops being the signed-in one throws [AccountNotActiveException].
     */
    suspend fun collections(
        session: Session,
        account: LibraryAccount,
    ): CollectionResult = collectionPass().run(session, account)

    private fun collectionPass(): CollectionPass {
        val interval = repository.collectionRelist.interval
        return CollectionPass(repository, server, clock, pageSize, margin, interval)
    }

    @Suppress("ReturnCount", "TooManyFunctions") // Early returns carry a failure out of the loops.
    private inner class Pass(
        private val session: Session,
        private val account: LibraryAccount,
    ) {
        private var skipped = 0
        private var passed = PassSummary()
        private var reconciled = ReconcileSummary()

        suspend fun incremental(
            chosen: Set<ItemId>,
            last: Instant,
            started: Instant,
        ): StayResult<PassSummary> {
            val libraries =
                when (val listed = server.libraries(session)) {
                    is ServerResult.Success -> listed.value.filter { it.id in chosen }
                    is ServerResult.Failure -> return StayResult(fail(listed.error), passed)
                }
            // A last success ahead of now means the clock went back: count from the older of the two.
            val since = minOf(last, started) - margin
            for (library in libraries) {
                val failed =
                    walk(library, since) { items ->
                        passed += repository.upsertItems(account, library.id, items)
                        true
                    }
                if (failed != null) return StayResult(failed, passed)
                // The series follow the library's items, from the same cursor; a failed page fails the pass.
                val seriesFailed = walkSeries(library, since)
                if (seriesFailed != null) return StayResult(seriesFailed, passed)
            }
            currentCoroutineContext().ensureActive()
            repository.finishSync(account, started)
            return StayResult(SyncOutcome.Completed, passed)
        }

        suspend fun reconcile(chosen: Set<ItemId>): StayResult<ReconcileSummary> {
            val libraries =
                when (val listed = server.libraries(session)) {
                    is ServerResult.Success -> listed.value.filter { it.id in chosen }
                    is ServerResult.Failure -> return StayResult(fail(listed.error), reconciled)
                }
            for (library in libraries) {
                val failed = reconcile(library)
                if (failed != null) return StayResult(failed, reconciled)
            }
            currentCoroutineContext().ensureActive()
            repository.clearFailure(account)
            return StayResult(SyncOutcome.Completed, reconciled)
        }

        private suspend fun reconcile(library: Library): SyncOutcome? {
            // The cache first, then the server: whatever another pass saves while the list is read is not "gone".
            val live = repository.liveItemIds(account, library.id).toSet()
            val tombstoned = repository.tombstonedItemIds(account, library.id).toSet()
            val known = repository.allItemIds(account).toSet()
            val itemIds: IdLister = { start, count -> server.libraryItemIds(session, library, start, count) }
            val items =
                when (val decided = decide(live, tombstoned, itemIds)) {
                    is Decision.Stop -> return decided.outcome
                    is Decision.Ready -> decided
                }
            // The series are a separate pass with their own sets (Vimes M3), read after the items'. If it stops, the
            // items' decision is still written, and the pass ends with the series' outcome.
            val seriesLive = repository.liveSeriesIds(account, library.id).toSet()
            val seriesTombstoned = repository.tombstonedSeriesIds(account, library.id).toSet()
            val seriesIds: IdLister = { start, count -> server.librarySeriesIds(session, library, start, count) }
            val series = decide(seriesLive, seriesTombstoned, seriesIds)
            val seriesGone = (series as? Decision.Ready)?.gone.orEmpty()
            val seriesReturning = (series as? Decision.Ready)?.returning.orEmpty()
            // One transaction for both (the item reconcile and the series reconcile).
            repository.applyReconcile(account, items.gone, items.returning, seriesGone, seriesReturning)
            reconciled +=
                ReconcileSummary(
                    tombstoned = items.gone.size,
                    revived = items.returning.size,
                    seriesTombstoned = seriesGone.size,
                    seriesRevived = seriesReturning.size,
                )
            // The item fetch runs whatever became of the series read (Granny M1), then the series' own recovery.
            val itemsFailed = fetchMissing(library, items.list, items.list.ids - known)
            if (itemsFailed != null) return itemsFailed
            if (series is Decision.Stop) return series.outcome
            val seriesKnown = repository.allSeriesIds(account).toSet()
            return fetchMissingSeries(library, (series as Decision.Ready).list.ids - seriesKnown)
        }

        // Inline, so the item listing's and the series listing's lambdas are not two classes behind one call in a loop
        // (ART 7.1, #264).
        // Reads the complete ID list with [list] and decides, under the guards both reconciles share: a read that fails
        // or is suspect (empty for a library that had rows) stops; what is gone must be missing from a second read too.
        private suspend inline fun decide(
            live: Set<ItemId>,
            tombstoned: Set<ItemId>,
            list: IdLister,
        ): Decision {
            val first =
                when (val read = readIds(list)) {
                    is IdsRead.Failed -> return Decision.Stop(fail(read.error))
                    is IdsRead.Complete -> read.list
                }
            // Nothing at all, for a library that had rows, is more likely a server fault than an emptied library.
            if (first.ids.isEmpty() && live.isNotEmpty()) return Decision.Stop(suspect())
            var gone = if (first.trusted) live - first.ids else emptySet()
            if (gone.isNotEmpty()) {
                // A shift that leaves the size unchanged looks sound once: only what a second read also lacks is gone.
                val again =
                    when (val read = readIds(list)) {
                        is IdsRead.Failed -> return Decision.Stop(fail(read.error))
                        is IdsRead.Complete -> read.list
                    }
                if (again.ids.isEmpty()) return Decision.Stop(suspect())
                gone = if (again.trusted) gone - again.ids else emptySet()
            }
            return Decision.Ready(first, gone, tombstoned intersect first.ids)
        }

        // The whole list or nothing: a failure part way, a page that adds nothing, or a list far past its total fails.
        private suspend inline fun readIds(list: IdLister): IdsRead {
            val ids = LinkedHashSet<ItemId>()
            var read = 0
            var total: Int? = null
            while (true) {
                when (val page = list(read, pageSize)) {
                    is ServerResult.Failure -> {
                        return IdsRead.Failed(page.error)
                    }

                    is ServerResult.Success -> {
                        if (read == 0) total = page.value.totalCount
                        val listed = page.value.ids
                        if (listed.isEmpty()) {
                            val sound = total != null && ids.size == total && read == total
                            return IdsRead.Complete(IdList(ids, trusted = sound))
                        }
                        val before = ids.size
                        ids += listed
                        read += listed.size
                        if (ids.size == before || read > (total ?: 0) + pageSize) {
                            return IdsRead.Failed(MediaServerError.ServerError())
                        }
                    }
                }
            }
        }

        // Walks the library for the items the account lacks and saves only those, stopping when it has them all. What
        // the walk cannot find is remembered, so the next reconcile of the same list does not walk again.
        private suspend fun fetchMissing(
            library: Library,
            list: IdList,
            missing: Set<ItemId>,
        ): SyncOutcome? {
            val wanted = (missing - unfindable.skip(account, library.id, list.fingerprint)).toMutableSet()
            if (wanted.isEmpty()) return null
            val order = list.ids.toList()
            val given = mutableSetOf<ItemId>()
            val failed =
                walk(
                    library,
                    since = null,
                    onMalformed = { index -> order.getOrNull(index)?.let { if (wanted.remove(it)) given += it } },
                ) { items ->
                    val found = items.filter { it.id in wanted }
                    if (found.isNotEmpty()) {
                        repository.upsertItems(account, library.id, found)
                        wanted -= found.map { it.id }.toSet()
                        reconciled += ReconcileSummary(fetched = found.size)
                    }
                    wanted.isNotEmpty()
                }
            if (failed == null) unfindable.remember(account, library.id, list.fingerprint, wanted + given)
            return failed
        }

        /**
         * Reads [library]'s item pages from the start (all of them, or those saved since [since]) and gives each to
         * [onPage], which says whether to go on. A page that will not decode is skipped item by item ([onMalformed]
         * is told the position), up to the limit. Null when the walk ended, or the failure that stopped it.
         */
        private suspend fun walk(
            library: Library,
            since: Instant?,
            onMalformed: (Int) -> Unit = {},
            onPage: suspend (List<MediaItem>) -> Boolean,
        ): SyncOutcome? {
            val limit = PageLimit(pageSize)
            var next = 0
            while (true) {
                when (val fetched = server.fetchPage(session, library, next, since, limit)) {
                    is Fetched.Failed -> {
                        return fail(fetched.error)
                    }

                    Fetched.Malformed -> {
                        if (++skipped > maxSkippedItems) return fail(MediaServerError.NotJellyfin())
                        onMalformed(next)
                        next += 1
                    }

                    is Fetched.Page -> {
                        if (fetched.items.isEmpty()) return null
                        next += fetched.items.size
                        if (!onPage(fetched.items)) return null
                    }
                }
            }
        }

        // Walks the series of [library] from the start and saves only the [missing] ones, stopping at the last.
        // The series the cache lacks were skipped in a walk, or came in a page that would not decode (Vimes M-1).
        private suspend fun fetchMissingSeries(
            library: Library,
            missing: Set<ItemId>,
        ): SyncOutcome? {
            if (missing.isEmpty()) return null
            val wanted = missing.toMutableSet()
            val limit = PageLimit(pageSize)
            var next = 0
            while (wanted.isNotEmpty()) {
                when (val fetched = server.fetchSeriesPage(session, library, next, null, limit)) {
                    is Fetched.Failed -> {
                        return fail(fetched.error)
                    }

                    Fetched.Malformed -> {
                        if (++skipped > maxSkippedItems) return fail(MediaServerError.NotJellyfin())
                        next += 1
                    }

                    is Fetched.Page -> {
                        if (fetched.items.isEmpty()) return null
                        next += fetched.items.size
                        val found = fetched.items.filter { it.id in wanted }
                        if (found.isNotEmpty()) {
                            repository.upsertSeries(account, library.id, found)
                            wanted -= found.map { it.id }.toSet()
                        }
                    }
                }
            }
            return null
        }

        // Reads the series of [library] saved since [since], saving each page. Null when it reached the end.
        private suspend fun walkSeries(
            library: Library,
            since: Instant,
        ): SyncOutcome? {
            val limit = PageLimit(pageSize)
            var next = 0
            while (true) {
                when (val fetched = server.fetchSeriesPage(session, library, next, since, limit)) {
                    is Fetched.Failed -> {
                        return fail(fetched.error)
                    }

                    Fetched.Malformed -> {
                        if (++skipped > maxSkippedItems) return fail(MediaServerError.NotJellyfin())
                        next += 1
                    }

                    is Fetched.Page -> {
                        if (fetched.items.isEmpty()) return null
                        next += fetched.items.size
                        repository.upsertSeries(account, library.id, fetched.items)
                    }
                }
            }
        }

        private suspend fun suspect(): SyncOutcome {
            repository.failSync(account, SyncFailure.Suspect)
            return SyncOutcome.Failed(SyncFailure.Suspect)
        }

        private suspend fun fail(error: MediaServerError): SyncOutcome {
            val failure = error.toSyncFailure()
            repository.failSync(account, failure)
            return SyncOutcome.Failed(failure)
        }
    }
}
