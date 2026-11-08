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

/**
 * What a pass did with the items it was given: new rows, rows that differed, and rows that were already identical; and
 * [skipped], the items and series a walk passed over because they would not decode.
 */
internal data class PassSummary(
    val inserted: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val skipped: Int = 0,
) {
    operator fun plus(write: PageWrite): PassSummary {
        val added = inserted + write.inserted
        return PassSummary(added, updated + write.updated, unchanged + write.unchanged, skipped)
    }
}

/**
 * What a reconciliation did: items tombstoned, tombstones cleared (another library's taken over included), and items
 * the cache lacked that were fetched; for the series pass, series tombstoned and revived; and items [moved] to a
 * library that still lists them instead of being tombstoned (spec 26, schema 11).
 */
internal data class ReconcileSummary(
    val tombstoned: Int = 0,
    val revived: Int = 0,
    val fetched: Int = 0,
    val seriesTombstoned: Int = 0,
    val seriesRevived: Int = 0,
    val moved: Int = 0,
) {
    operator fun plus(other: ReconcileSummary) =
        ReconcileSummary(
            tombstoned + other.tombstoned,
            revived + other.revived,
            fetched + other.fetched,
            seriesTombstoned + other.seriesTombstoned,
            seriesRevived + other.seriesRevived,
            moved + other.moved,
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

/** How one read of which libraries exist ended: [Sound] with the IDs, or [Failed] with the outcome the pass ends as. */
private sealed interface Existing {
    class Sound(
        val ids: Set<ItemId>,
    ) : Existing

    class Failed(
        val outcome: SyncOutcome,
    ) : Existing
}

/**
 * What reading one library's unlisted-but-named series came to, as the reconcile uses it: [kept] are the candidates not
 * known to be gone, [failure] the error that stopped the reads.
 */
private class HiddenSeries(
    val kept: Set<ItemId>,
    val absent: Set<ItemId>,
    val failure: MediaServerError?,
) {
    companion object {
        val NONE = HiddenSeries(emptySet(), emptySet(), null)
    }
}

// An empty list for a library that had series.
private fun listsNothing(
    ready: Decision.Ready?,
    live: Set<ItemId>,
): Boolean = ready != null && ready.listedNothing && live.isNotEmpty()

// The series a reconcile tombstones: those the list lacks and the by-ID reads do not keep. With an empty list for a
// library that had series, only the hidden ones the server itself twice said are not there.
private fun goneSeries(
    ready: Decision.Ready?,
    hidden: HiddenSeries,
    emptyList: Boolean,
): Set<ItemId> {
    val gone = ready?.gone.orEmpty()
    return if (emptyList) gone intersect hidden.absent else gone - hidden.kept
}

/** One page of an ID listing, from a start index and a count: the item listing or the series listing. */
private typealias IdLister = suspend (start: Int, count: Int) -> ServerResult<ItemIdPage>

/**
 * What one complete, guarded ID read of items or series decided: what is [gone], and what is [returning].
 * [listedNothing] is set when either read of the list came back empty.
 */
private sealed interface Decision {
    class Ready(
        val list: IdList,
        val gone: Set<ItemId>,
        val returning: Set<ItemId>,
        val listedNothing: Boolean = false,
    ) : Decision

    /** The read failed or was suspect: [outcome] is what the pass ends as, and nothing is to be written for it. */
    class Stop(
        val outcome: SyncOutcome,
    ) : Decision
}

/**
 * How many pages' worth of items in a row the studios re-read may find undecodable before it gives up (spec 18, #460).
 * The re-read has no skip cap, so a server or proxy that answers every index with unreadable content would otherwise
 * make it step one item at a time until WorkManager's window ended it, every run. More than this many pages in a row
 * ends the re-read as a failure that is not recorded in the account's sync state, with the flag left set.
 *
 * It is a bound against a proxy or server that sends garbage, not against a hostile server: one that interleaves good
 * items, reports a false total or never ends a listing is not stopped by it. The accepted cost: a library that truly
 * holds more than a page of undecodable items in a row keeps the flag set, and the re-read is tried again every run.
 */
internal const val BACKFILL_MALFORMED_PAGES_MAX = 1

/** What one walk has seen of undecodable items: the run since the last good page, the total, and the server's count. */
private class MalformedRun {
    var inARow = 0
    var skipped = 0
    var total: Int? = null

    fun page(totalCount: Int?) {
        inARow = 0
        if (totalCount != null) total = totalCount
    }

    fun skip() {
        inARow += 1
        skipped += 1
    }
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
    private val margin: Duration = LibraryPass.DEFAULT_MARGIN,
    private val maxSkippedItems: Int = LibraryPass.MAX_SKIPPED_ITEMS,
    private val unfindable: UnfindableIds = UnfindableIds(),
    private val report: (String) -> Unit = {},
    private val hiddenMemory: HiddenSeriesMemory = HiddenSeriesMemory(),
) {
    init {
        require(pageSize in 1..MediaServer.MAX_PAGE_SIZE) { "pageSize must be 1 to ${MediaServer.MAX_PAGE_SIZE}" }
    }

    /**
     * Fetches what changed in [chosen] since the last successful pass (less the margin) for [account] and saves it.
     * Ends as [SyncOutcome.Cancelled] when there is nothing to top up (the first sync has not finished), the account
     * stopped being the signed-in one, or a first sync started while it ran (its state is then left alone).
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
     * The studios re-read (spec 18, ruling R9): while the account's `backfillPending` flag is set, reads every item and
     * series of [chosen] again from the start and saves them, then clears the flag if every chosen library was walked.
     * It is its own step so it can never hold up the other passes: it never moves the recorded moment and never
     * changes the account's sync state, so its failure is only returned. An item or series that will not decode is
     * counted in [PassSummary.skipped] and passed over (it keeps no studios), never a failure. Ends as
     * [SyncOutcome.Completed] with nothing asked when the flag is clear, and as [SyncOutcome.Cancelled] when the first
     * sync has not finished, one started while it ran (the flag then stays set), or the account stopped being the
     * signed-in one.
     */
    suspend fun backfill(
        session: Session,
        account: LibraryAccount,
        chosen: Set<ItemId>,
    ): StayResult<PassSummary> =
        try {
            when {
                repository.lastSuccess(account) == null || repository.isFirstSyncRunning(account) -> {
                    StayResult(SyncOutcome.Cancelled, PassSummary())
                }

                !repository.backfillPending(account) -> {
                    StayResult(SyncOutcome.Completed, PassSummary())
                }

                else -> {
                    Pass(session, account, backfilling = true).backfill(chosen)
                }
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
     * the server, so what another pass saves meanwhile cannot look deleted. A chosen library the server no longer lists
     * is tombstoned whole under the same guards (#495). It never moves the recorded moment.
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

    // [backfilling]: the studios re-read, which records nothing in the account's state and skips without a limit.
    @Suppress("ReturnCount", "TooManyFunctions") // Early returns carry a failure out of the loops.
    private inner class Pass(
        private val session: Session,
        private val account: LibraryAccount,
        private val backfilling: Boolean = false,
    ) {
        private var skipped = 0
        private var passed = PassSummary()
        private var reconciled = ReconcileSummary()

        // Set when a library row refused one of this pass's writes (spec 26, schema 11): a library dropped, held or
        // stamped since the pass read the choice. Nothing was written; the pass stops as Cancelled, with no failure
        // recorded and neither the recorded moment nor any flag moved, so the next run tries again (MED-8).
        private var refused = false

        // What a write did, or null when the library row refused it (and the pass is then stopping).
        private fun accepted(result: PageResult): PageResult.Written? {
            if (result is PageResult.Written) return result
            refused = true
            return null
        }

        // The chosen libraries this pass walks, read from the stored rows at the start of the pass. A library marked
        // for a rewalk is left to the rewalk (spec 26, §4 step 4). One that still has an add stamp or a hold is left
        // out too (Task 4.5, binding item 1): the selection saved it as chosen a moment before landing it, its add
        // walk read it, and a stamp-null write would be refused and end the whole pass Cancelled.
        private suspend fun walkable(chosen: Set<ItemId>): Set<ItemId> {
            val leftOut =
                repository
                    .storedLibraries(account)
                    .filter { it.rewalkPending || it.addStamp != null || it.heldUntilMs != null }
                    .map { it.id }
                    .toSet()
            return chosen - leftOut
        }

        // The item and series IDs the listed libraries' complete lists held in this pass.
        private val listedItems = HashSet<ItemId>()
        private val listedSeries = HashSet<ItemId>()

        // Set when a listed library's ID list was not trusted (its size did not match what the server said).
        private var untrusted = false

        suspend fun incremental(
            chosen: Set<ItemId>,
            last: Instant,
            started: Instant,
        ): StayResult<PassSummary> {
            val walking = walkable(chosen)
            // Each library's own coverage (a hold placed, an add or a rewalk that landed), read before the server.
            val coverage = repository.coveredFrom(account)
            val libraries =
                when (val listed = server.libraries(session)) {
                    is ServerResult.Success -> listed.value.filter { it.id in walking }
                    is ServerResult.Failure -> return StayResult(fail(listed.error), passed)
                }
            // A last success ahead of now means the clock went back: count from the older of the two.
            val accountSince = minOf(last, started) - margin
            for (library in libraries) {
                // A library covered from earlier than the account's window reads from there (schema 11).
                val covered = coverage[library.id]?.let { Instant.fromEpochMilliseconds(it) - margin }
                val since = if (covered == null) accountSince else minOf(accountSince, covered)
                val failed =
                    walk(library, since) { items ->
                        val written = repository.upsertItems(account, library.id, stamp = null, rewalk = false, items)
                        accepted(written)?.let { passed += it.items }
                        !refused
                    }
                if (failed != null) return StayResult(failed, passed)
                // The series follow the library's items, from the same cursor; a failed page fails the pass.
                val seriesFailed = walkSeries(library, since)
                if (seriesFailed != null) return StayResult(seriesFailed, passed)
                // The series its episodes name that the series list leaves out (issue 604), read by ID.
                val hiddenFailed = readNewHiddenSeries(library)
                if (hiddenFailed != null) return StayResult(hiddenFailed, passed)
            }
            currentCoroutineContext().ensureActive()
            // A first sync that started meanwhile owns the account: this pass stops rather than race it. The coverage
            // of each library walked is cleared with the recorded moment, if it is still what was read above.
            val walked = libraries.map { it.id }.toSet()
            val finished = repository.finishIncremental(account, started, coverage.filterKeys { it in walked })
            return StayResult(if (finished) SyncOutcome.Completed else SyncOutcome.Cancelled, passed)
        }

        suspend fun backfill(chosen: Set<ItemId>): StayResult<PassSummary> {
            val walking = walkable(chosen)
            val libraries =
                when (val listed = server.libraries(session)) {
                    is ServerResult.Success -> listed.value.filter { it.id in walking }
                    is ServerResult.Failure -> return StayResult(fail(listed.error), counted())
                }
            for (library in libraries) {
                val failed =
                    walk(library, since = null) { items ->
                        val written = repository.upsertItems(account, library.id, stamp = null, rewalk = false, items)
                        accepted(written)?.let { passed += it.items }
                        !refused
                    }
                if (failed != null) return StayResult(failed, counted())
                val seriesFailed = walkSeries(library, since = null)
                if (seriesFailed != null) return StayResult(seriesFailed, counted())
            }
            currentCoroutineContext().ensureActive()
            // The flag clears only if every chosen library is covered: walked here, or marked for a rewalk, which reads
            // it from the start with its studios (spec 26, Task 4.4b). One the server did not list keeps it set.
            val allWalked = walking.all { id -> libraries.any { it.id == id } }
            val finished = repository.finishBackfill(account, allWalked)
            return StayResult(if (finished) SyncOutcome.Completed else SyncOutcome.Cancelled, counted())
        }

        private fun counted() = passed.copy(skipped = skipped)

        suspend fun reconcile(everyChosen: Set<ItemId>): StayResult<ReconcileSummary> {
            // A marked library is neither reconciled nor taken for removed: the rewalk reads it from the start. Nor is
            // one still stamped or held: its rows are the add's or the hold's, not this pass's.
            val chosen = walkable(everyChosen)
            val libraries =
                when (val listed = server.libraries(session)) {
                    is ServerResult.Success -> listed.value.filter { it.id in chosen }
                    is ServerResult.Failure -> return StayResult(fail(listed.error), reconciled)
                }
            for (library in libraries) {
                val failed = reconcile(library, libraries)
                if (failed != null) return StayResult(failed, reconciled)
            }
            // A chosen library the listing lacks may have been deleted on the server (#495). This runs only once every
            // listed library was reconciled, so it knows which IDs those libraries still hold.
            val removed = chosen - libraries.map { it.id }.toSet()
            if (removed.isNotEmpty()) {
                val failed = tombstoneRemoved(chosen, removed)
                if (failed != null) return StayResult(failed, reconciled)
            }
            currentCoroutineContext().ensureActive()
            repository.clearFailure(account)
            return StayResult(SyncOutcome.Completed, reconciled)
        }

        // A chosen library the listing lacks is tombstoned (items and series, revivable), but only if the server, asked
        // about the library IDs themselves (not the user's views, which hide and group), also says it is gone. The
        // answer must look sound: one with none of the chosen libraries is more likely a server fault (suspect). That
        // is a deliberate fail-safe, and it also covers every chosen library being truly deleted: the pass then fails
        // as suspect on every run and tombstones nothing (follow-up #507). Like
        // the ID reconcile, only what a second read also lacks is gone; the two reads are back to back, so a server
        // that is briefly wrong for both is the accepted risk. The library stays chosen, so if it comes back its rows
        // revive in the ordinary reconcile. An item or series that a listed library still holds (rows have one
        // library, items can belong to two) is left live, and a library whose rows are all so held costs no request.
        // Skipped when a listed library's ID list could not be trusted, as then what it holds is not known. Every
        // removed library is written in one transaction, so the generation moves once.
        private suspend fun tombstoneRemoved(
            chosen: Set<ItemId>,
            removed: Set<ItemId>,
        ): SyncOutcome? {
            if (untrusted) return null
            // The cache first, then the server, as in the ID reconcile.
            val live =
                removed
                    .associateWith {
                        repository.liveItemIds(account, it).filterNot { id -> id in listedItems } to
                            repository.liveSeriesIds(account, it).filterNot { id -> id in listedSeries }
                    }.filterValues { (items, series) -> items.isNotEmpty() || series.isNotEmpty() }
            if (live.isEmpty()) return null
            val first =
                when (val read = existing(chosen)) {
                    is Existing.Failed -> return read.outcome
                    is Existing.Sound -> read.ids
                }
            val missing = live.filterKeys { it !in first }
            if (missing.isEmpty()) return null
            val again =
                when (val read = existing(chosen)) {
                    is Existing.Failed -> return read.outcome
                    is Existing.Sound -> read.ids
                }
            val gone = missing.filterKeys { it !in again }
            val series = gone.values.flatMap { it.second }
            val write = repository.applyRemoved(account, gone.mapValues { it.value.first }, series)
            if (write !is ReconcileApplied.Written) {
                refused = true
                return SyncOutcome.Cancelled
            }
            // What the write tombstoned: an item another pass got to first is not counted.
            reconciled += ReconcileSummary(tombstoned = write.tombstoned, seriesTombstoned = series.size)
            return null
        }

        // One read of which chosen libraries exist: a failure, or an answer with none of them (suspect), stops.
        private suspend fun existing(chosen: Set<ItemId>): Existing =
            when (val read = server.existingLibraryIds(session, chosen)) {
                is ServerResult.Failure -> {
                    Existing.Failed(fail(read.error))
                }

                is ServerResult.Success -> {
                    // Tested against the chosen IDs, not just for emptiness, to defend against an adapter that
                    // returns IDs that were not asked about.
                    val sound = read.value.any { it in chosen }
                    if (sound) Existing.Sound(read.value) else Existing.Failed(suspect())
                }
            }

        // One library's items, then its series, each guarded; the early returns are the guards' stops.
        @Suppress("CyclomaticComplexMethod")
        private suspend fun reconcile(
            library: Library,
            reconciling: List<Library>,
        ): SyncOutcome? {
            // The cache first, then the server: whatever another pass saves while the list is read is not "gone".
            // [also] are the items the library lists and another owns (spec 26, schema 11): they are held like its own
            // for the guards, so a row goes only when both reads lack the item.
            val live = repository.liveItemIds(account, library.id).toSet()
            val tombstoned = repository.tombstonedItemIds(account, library.id).toSet()
            val also = repository.alsoListedIds(account, library.id).toSet()
            val known = repository.allItemIds(account).toSet()
            val itemIds: IdLister = { start, count -> server.libraryItemIds(session, library, start, count) }
            val held = live + also
            val items =
                when (val decided = decide(held, tombstoned, itemIds, suspectLive = held)) {
                    is Decision.Stop -> return decided.outcome
                    is Decision.Ready -> decided
                }
            // The series are a separate pass with their own sets (Vimes M3), read after the items'. If it stops, the
            // items' decision is still written, and the pass ends with the series' outcome.
            listedItems += items.list.ids
            if (!items.list.trusted) untrusted = true
            val seriesLive = repository.liveSeriesIds(account, library.id).toSet()
            val seriesTombstoned = repository.tombstonedSeriesIds(account, library.id).toSet()
            val seriesIds: IdLister = { start, count -> server.librarySeriesIds(session, library, start, count) }
            // A stored series that a live episode names may be one the list leaves out (issue 604), so it says nothing
            // about whether the list is sane: the suspect check looks at the series the list itself should hold.
            val referenced = repository.referencedSeriesIds(account, library.id).toSet()
            // The emptiness guard is applied below, so that the hidden series are still read when the list is empty.
            val series = decide(seriesLive, seriesTombstoned, seriesIds, suspectLive = emptySet())
            // Whatever a live episode here names and is held live is not for the removed-library check to tombstone.
            listedSeries += referenced intersect repository.liveSeriesIdsAnywhere(account).toSet()
            // A series the list leaves out is not gone while a live episode names it and the server still has it
            // (issue 604): each is read by ID, and the ones found are stored (again) rather than tombstoned. A series
            // that could not be asked about is kept as well, so a failed read tombstones nothing.
            val ready = series as? Decision.Ready
            val hidden = ready?.let { confirmHidden(library, reconciling, it.list.ids) } ?: HiddenSeries.NONE
            // The library row refused the found series: the library changed under the pass, which writes nothing more.
            if (refused) return SyncOutcome.Cancelled
            ready?.let {
                listedSeries += it.list.ids + hidden.kept
                if (!it.list.trusted) untrusted = true
            }
            // An empty list for a library that had series is more likely a server fault than an emptied library: no
            // series is tombstoned for it this pass, except a hidden one the server itself twice said is not there.
            val emptyList = listsNothing(ready, seriesLive)
            val seriesGone = goneSeries(ready, hidden, emptyList)
            val seriesReturning = ready?.returning.orEmpty()
            // One transaction for both (the item reconcile and the series reconcile).
            val owned = OwnedSets(live, also, known)
            if (!writeDecisions(library, items, owned, seriesGone, seriesReturning)) return SyncOutcome.Cancelled
            // The item fetch runs whatever became of the series read (Granny M1), then the series' own recovery.
            val itemsFailed = fetchMissing(library, items.list, items.list.ids - known)
            if (itemsFailed != null) return itemsFailed
            if (series is Decision.Stop) return series.outcome
            hidden.failure?.let { return fail(it) }
            // Only series no episode names are evidence of a faulty list (the others are read by ID above).
            if (emptyList && (seriesLive - referenced).isNotEmpty()) return suspect()
            val seriesKnown = repository.allSeriesIds(account).toSet()
            return fetchMissingSeries(library, (series as Decision.Ready).list.ids - seriesKnown)
        }

        // What the reconcile read of the cache before the server: the library's [live] items, the ones it lists that
        // another owns ([also]), and every item the account holds ([known]).
        private inner class OwnedSets(
            val live: Set<ItemId>,
            val also: Set<ItemId>,
            val known: Set<ItemId>,
        )

        // Writes one library's decisions with its also-listed rows (spec 26, schema 11): the items it owns that are
        // gone, the rows of the others it lists no more, and a row for each listed item another library holds (a
        // tombstone of which it takes over). False, with nothing written, when the library row refused it: the
        // library changed under the pass, which writes nothing more.
        private suspend fun writeDecisions(
            library: Library,
            items: Decision.Ready,
            owned: OwnedSets,
            seriesGone: Set<ItemId>,
            seriesReturning: Set<ItemId>,
        ): Boolean {
            val itemsGone = items.gone intersect owned.live
            val alsoAdd = (items.list.ids intersect owned.known) - owned.live - owned.also
            val write =
                repository.applyReconcile(
                    account,
                    library.id,
                    itemsGone,
                    items.returning,
                    seriesGone,
                    seriesReturning,
                    alsoAdd = alsoAdd,
                    alsoDrop = items.gone intersect owned.also,
                )
            val written = write as? ReconcileApplied.Written
            if (written == null) {
                refused = true
                return false
            }
            reconciled +=
                ReconcileSummary(
                    tombstoned = written.tombstoned,
                    revived = written.revived + written.takenOver,
                    seriesTombstoned = seriesGone.size,
                    seriesRevived = seriesReturning.size,
                    moved = written.moved,
                )
            return true
        }

        private suspend fun confirmHidden(
            library: Library,
            reconciling: List<Library>,
            listed: Set<ItemId>,
        ): HiddenSeries {
            val candidates = repository.unlistedReferencedSeries(account, library, listed, reconciling)
            val read =
                server.readHiddenSeries(
                    session,
                    repository,
                    account,
                    library,
                    candidates,
                    hiddenMemory,
                    confirmAbsent = true,
                    stamp = null,
                    rewalk = false,
                )
            if (read.asked > 0) report(read.line())
            read.refused?.let { accepted(it) }
            return HiddenSeries(read.keep(candidates), read.absent, read.failure)
        }

        // The incremental pass: the named series that are stored nowhere yet (a series already held is refreshed by
        // the reconcile, which reads each unlisted one on every open). The negative cache is bounded because that
        // reconcile runs on every open and forgets it.
        private suspend fun readNewHiddenSeries(library: Library): SyncOutcome? {
            val missing = repository.unstoredReferencedSeries(account, library)
            val read =
                server.readHiddenSeries(
                    session,
                    repository,
                    account,
                    library,
                    missing,
                    hiddenMemory,
                    confirmAbsent = false,
                    stamp = null,
                    rewalk = false,
                )
            if (read.asked > 0) report(read.line())
            read.refused?.let { accepted(it) }
            if (refused) return SyncOutcome.Cancelled
            return read.failure?.let { fail(it) }
        }

        // Inline, so the item listing's and the series listing's lambdas are not two classes behind one call in a loop
        // (ART 7.1, #264).
        // Reads the complete ID list with [list] and decides, under the guards both reconciles share: a read that fails
        // or is suspect (empty for a library that had rows) stops; what is gone must be missing from a second read too.
        private suspend inline fun decide(
            live: Set<ItemId>,
            tombstoned: Set<ItemId>,
            list: IdLister,
            suspectLive: Set<ItemId> = live,
        ): Decision {
            val first =
                when (val read = readIds(list)) {
                    is IdsRead.Failed -> return Decision.Stop(fail(read.error))
                    is IdsRead.Complete -> read.list
                }
            // Nothing at all, for a library that had rows, is more likely a server fault than an emptied library.
            if (first.ids.isEmpty() && suspectLive.isNotEmpty()) return Decision.Stop(suspect())
            var gone = if (first.trusted) live - first.ids else emptySet()
            var listedNothing = first.ids.isEmpty()
            if (gone.isNotEmpty()) {
                // A shift that leaves the size unchanged looks sound once: only what a second read also lacks is gone.
                val again =
                    when (val read = readIds(list)) {
                        is IdsRead.Failed -> return Decision.Stop(fail(read.error))
                        is IdsRead.Complete -> read.list
                    }
                if (again.ids.isEmpty() && suspectLive.isNotEmpty()) return Decision.Stop(suspect())
                listedNothing = listedNothing || again.ids.isEmpty()
                gone = if (again.trusted) gone - again.ids else emptySet()
            }
            return Decision.Ready(first, gone, tombstoned intersect first.ids, listedNothing)
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
                        val written = repository.upsertItems(account, library.id, stamp = null, rewalk = false, found)
                        if (accepted(written) == null) return@walk false
                        wanted -= found.map { it.id }.toSet()
                        reconciled += ReconcileSummary(fetched = found.size)
                    }
                    wanted.isNotEmpty()
                }
            // A refused write ends the walk as Cancelled, so nothing is given up on.
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
            val run = MalformedRun()
            var next = 0
            while (true) {
                when (val fetched = server.fetchPage(session, library, next, since, limit)) {
                    is Fetched.Failed -> {
                        return fail(fetched.error)
                    }

                    Fetched.Malformed -> {
                        skip(run)?.let { return it }
                        onMalformed(next)
                        next += 1
                    }

                    is Fetched.Page -> {
                        run.page(fetched.totalCount)
                        if (fetched.items.isEmpty()) return null
                        next += fetched.items.size
                        // A page the library row refused ends the walk, and the pass, as Cancelled.
                        if (!onPage(fetched.items)) return if (refused) SyncOutcome.Cancelled else null
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
            val run = MalformedRun()
            var next = 0
            while (wanted.isNotEmpty()) {
                when (val fetched = server.fetchSeriesPage(session, library, next, null, limit)) {
                    is Fetched.Failed -> {
                        return fail(fetched.error)
                    }

                    Fetched.Malformed -> {
                        skip(run)?.let { return it }
                        next += 1
                    }

                    is Fetched.Page -> {
                        run.page(fetched.totalCount)
                        if (fetched.items.isEmpty()) return null
                        next += fetched.items.size
                        val found = fetched.items.filter { it.id in wanted }
                        if (found.isNotEmpty()) {
                            val written =
                                repository.upsertSeries(account, library.id, stamp = null, rewalk = false, found)
                            if (accepted(written) == null) return SyncOutcome.Cancelled
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
            since: Instant?,
        ): SyncOutcome? {
            val limit = PageLimit(pageSize)
            val run = MalformedRun()
            var next = 0
            while (true) {
                when (val fetched = server.fetchSeriesPage(session, library, next, since, limit)) {
                    is Fetched.Failed -> {
                        return fail(fetched.error)
                    }

                    Fetched.Malformed -> {
                        skip(run)?.let { return it }
                        next += 1
                    }

                    is Fetched.Page -> {
                        run.page(fetched.totalCount)
                        if (fetched.items.isEmpty()) return null
                        next += fetched.items.size
                        val written =
                            repository.upsertSeries(account, library.id, stamp = null, rewalk = false, fetched.items)
                        if (accepted(written) == null) return SyncOutcome.Cancelled
                    }
                }
            }
        }

        // One item or series would not decode: past the limit that fails the pass. The backfill has no such limit, but
        // it gives up on more than a page's worth in a row, or on more skipped than the server said the listing holds:
        // that is a proxy or server answering with garbage, not a few odd items. It does not guard against a server
        // that means harm (see [BACKFILL_MALFORMED_PAGES_MAX]).
        private suspend fun skip(run: MalformedRun): SyncOutcome? {
            skipped += 1
            run.skip()
            if (!backfilling) {
                return if (skipped > maxSkippedItems) fail(MediaServerError.NotJellyfin()) else null
            }
            // The total is known only once page 0 of the walk decoded; until then only the in-a-row rule applies.
            val beyondTotal = run.total?.let { run.skipped > it } ?: false
            val stuck = run.inARow > pageSize * BACKFILL_MALFORMED_PAGES_MAX
            return if (stuck || beyondTotal) fail(MediaServerError.NotJellyfin()) else null
        }

        private suspend fun suspect(): SyncOutcome = failed(SyncFailure.Suspect)

        private suspend fun fail(error: MediaServerError): SyncOutcome = failed(error.toSyncFailure())

        // Recorded for the account, unless a first sync started meanwhile (its state is left alone) or this is the
        // backfill, whose failure is only returned.
        private suspend fun failed(failure: SyncFailure): SyncOutcome {
            if (!backfilling && !repository.failStayCurrent(account, failure)) return SyncOutcome.Cancelled
            return SyncOutcome.Failed(failure)
        }
    }
}
