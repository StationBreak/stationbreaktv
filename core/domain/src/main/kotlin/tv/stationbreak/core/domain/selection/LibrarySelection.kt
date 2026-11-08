// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.FirstSync
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.Missing
import tv.stationbreak.core.domain.SelectionWriter
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.StayCurrentRequests
import tv.stationbreak.core.domain.StoredLibrary
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.RecordReset
import tv.stationbreak.core.domain.lineup.repair.RepairOutcome
import tv.stationbreak.core.domain.lineup.repair.TodayRepairer
import tv.stationbreak.core.domain.lineup.repair.refuseInTurn
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.Session
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/** What the selection reports, by name only: never an ID, a name or a count that could tell one. */
enum class SelectionEvent {
    /** The resume at start failed; the next operation puts the library cache in line first. */
    SelectionResumeFailed,

    /** A cache write failed after the selection was saved: the next operation puts the cache in line first. */
    RoomBehind,

    /** An add whose read was never proven to end was set back to be read again. */
    UnprovenReverted,

    /** A library being added that was already read with no stamp was taken out of the adds. */
    AddDropped,

    /** A row the cache would refuse a new stamp was held first, then stamped. */
    HeldBeforeStamp,

    /** A step after a committed setup save failed (the add pass's restart, or the first sync's request). */
    SetupFollowUpFailed,

    /** The seq could not be raised above the cache's stamps at start; the next operation's floor covers it. */
    RaiseSkipped,
}

/**
 * The re-entry marker of the selection (spec 26, Part 4 §2): set while [LibrarySelection]'s lock is held, so a call of
 * the selection from inside it fails at once with [IllegalStateException] instead of waiting on itself.
 */
internal object InSelection : CoroutineContext.Element, CoroutineContext.Key<InSelection> {
    override val key: CoroutineContext.Key<*> get() = this
}

/**
 * The one writer of the library selection (spec 26, Part 4 §2): a change after setup, the setup itself, the landing of
 * an add, the following of the server's listing, the first sync's library set and the resume at start. Each writes
 * its stores in one order, the repair record first, then the settings (the truth), then the library cache, and runs
 * to its end once it has started (a caller that goes away does not stop it).
 *
 * - **One at a time.** Every operation but [resumeAtStart] first waits for the resume ([await]), then takes the
 *   selection's lock. The lock comes before the repairer's turn, so a call from inside either fails at once
 *   ([InSelection], and the repairer's own marker). A cache write that failed after a committed save is reported and
 *   put right by the next operation, before it does anything else.
 * - **The seq.** Every seq is allocated by the repairer, floored one above the highest add stamp the cache holds, read
 *   under the lock each time, so no seq ever equals a stamp left in the cache. Before any lineup exists there is no
 *   record to repair, and a change is a plain save (one above the stored seq and that floor). [selectionChanges] is
 *   the stored seq, published only once the save that moved it (and the cache write after it) is done.
 * - **The stamps.** A library ticked is added with the change's seq as its stamp; it is chosen only once its read has
 *   ended and [passLanded] saves it. A chosen library never keeps a stamp: a proven one is landed, an unproven one is
 *   set back to be read again with no stamp ([LibraryRepository.revertUnproven]), and stay current is asked to run.
 * - **The holds.** A library leaving the cache is held while a programme of it is pinned on air (the repairer's
 *   `heldPins`), never for less than the hold its row already has.
 *
 * Its callers see it through narrow ports: features through [LibraryChoices] and [LibrarySetup], the add pass through
 * [AddLandings], the first sync through [FirstSyncLibraries], stay current through [ServerListings], and the app start
 * through [SelectionResume]. Its writes reach the settings through [SelectionWriter] alone. [resumed] is the gate the
 * app binds as [SelectionReady].
 */
@Suppress("LongParameterList", "TooManyFunctions") // The stores and ports it writes, one for one; one per step.
class LibrarySelection(
    private val settings: ServerSettings,
    private val writer: SelectionWriter,
    private val library: LibraryRepository,
    private val server: MediaServer,
    private val session: () -> Session?,
    private val repairer: TodayRepairer,
    private val service: LineupService,
    private val source: BuiltInScheduleSource,
    private val passes: LibraryPasses,
    private val firstSync: FirstSync,
    private val stayCurrent: StayCurrentRequests,
    private val config: SelectionConfig = SelectionConfig(),
    private val clock: Clock = Clock.System,
    private val resumed: ResumeGate = ResumeGate(),
    private val report: (SelectionEvent) -> Unit = {},
) : LibraryChoices,
    LibrarySetup,
    AddLandings,
    FirstSyncLibraries,
    ServerListings,
    SelectionResume,
    SelectionReady {
    private val mutex = Mutex()
    private val view = MutableStateFlow<ChoiceView?>(null)
    private val seqs = MutableStateFlow(0L)

    // Set when a cache write failed after its save committed: the next operation reconciles the cache first.
    @Volatile private var roomBehind = false

    // The libraries the last counted listing gave: a library may be added only if it was listed.
    @Volatile private var lastListed: Set<ItemId>? = null

    override val selection: StateFlow<ChoiceView?> = view.asStateFlow()

    /**
     * The seq of the stored selection (D14, level-based): it moves with every save that changes what plays, and only
     * once that save (and its cache write) is done. The lineup refresher follows it.
     */
    val selectionChanges: StateFlow<Long> = seqs.asStateFlow()

    /** Returns once [resumeAtStart] has run, whether it changed anything or failed. */
    override suspend fun await() = resumed.await()

    // --- Entry ------------------------------------------------------------------------------------------------------

    // Refused from inside a repairer turn or this lock; waits for the resume; then under the lock, to its end, with
    // the cache put in line first when an earlier write left it behind.
    private suspend fun <T> locked(
        catchUpFirst: Boolean = true,
        block: suspend () -> T,
    ): T {
        refuseReentry()
        resumed.await()
        return mutex.withLock {
            withContext(NonCancellable + InSelection) {
                if (catchUpFirst && roomBehind) catchUp()
                block()
            }
        }
    }

    private suspend fun refuseReentry() {
        refuseInTurn()
        check(currentCoroutineContext()[InSelection] == null) { "Entered from inside the selection's own lock" }
    }

    private suspend fun activeAccount(): LibraryAccount? = library.activeAccount().first()

    private fun nowMs(): Long = clock.now().toEpochMilliseconds()

    // --- LibraryChoices ---------------------------------------------------------------------------------------------

    override suspend fun listing(): LibraryListing {
        refuseReentry()
        val held = session() ?: return LibraryListing.Unavailable(null, view.value)
        val listed = server.libraries(held)
        followServer(LibraryAccount(held.server.id, held.userId), listed)
        return when (listed) {
            is ServerResult.Success -> LibraryListing.Listed(listed.value, view.value)
            is ServerResult.Failure -> LibraryListing.Unavailable(listed.error, view.value)
        }
    }

    override suspend fun change(ticked: Set<ItemId>): ChangeResult =
        locked {
            val account = activeAccount() ?: return@locked ChangeResult.Failed
            // Stale (the lineup or its repairs moved while the repair ran): once more, from a fresh read.
            changeOnce(account, ticked) ?: changeOnce(account, ticked) ?: ChangeResult.Failed
        }

    // One attempt at a change; null when it was stale.
    @Suppress("ReturnCount") // Each early return is a reason nothing is stored.
    private suspend fun changeOnce(
        account: LibraryAccount,
        ticked: Set<ItemId>,
    ): ChangeResult? {
        val stored = settings.loadSelection() ?: return ChangeResult.Failed
        val chosen = stored.chosen intersect ticked
        if (chosen.isEmpty()) return ChangeResult.LastLibrary
        val kept = stored.adding.filterKeys { it in ticked }
        val added = ticked - stored.chosen - stored.adding.keys
        val listed = lastListed
        if (added.isNotEmpty() && (listed == null || !listed.containsAll(added))) return ChangeResult.NotListed
        if (chosen == stored.chosen && kept == stored.adding && added.isEmpty()) return ChangeResult.Saved
        val next = { seq: Long ->
            stored.copy(chosen = chosen, seq = seq, adding = kept + added.associateWith { seq }).withMissingKept()
        }
        // Today's repair only when a chosen library goes; an add or a dropped add changes no programme on air.
        val repaired = chosen.takeIf { it != stored.chosen }
        val saved =
            when (val outcome = allocate(account, stored, repaired, next)) {
                Allocated.Stale -> return null
                Allocated.Failed -> return ChangeResult.Failed
                is Allocated.Saved -> next(outcome.seq)
            }
        afterSave(account, stored, saved)
        return ChangeResult.Saved
    }

    // --- LibrarySetup -----------------------------------------------------------------------------------------------

    override suspend fun choose(ids: Set<ItemId>): SetupResult =
        locked {
            // A setup follows a clear (every sign-in path clears first): it never writes over a stored choice.
            if (ids.isEmpty() || settings.loadSelection() != null) return@locked SetupResult.Failed
            val account = activeAccount() ?: return@locked SetupResult.Failed
            // A listing of the last account's server says nothing about this choice.
            lastListed = null
            val atLeast = floorOf(library.storedLibraries(account))
            // The record is emptied first; the save is the setup's, with nothing being added, gone or missed.
            val outcome =
                repairer.resetSelection(account, atLeast) { seq -> write(StoredSelection(ids, seq, resetSeq = seq)) }
            if (outcome !is RepairOutcome.Saved) return@locked SetupResult.Failed
            // Any add under way was of the old choice. The save has committed: what follows cannot fail the setup.
            followUp { passes.restart() }
            room { branches(account, ids, library.storedLibraries(account)) }
            // The same account, its first sync long finished: nothing else would read the new libraries.
            followUp { firstSync.ensureRunning() }
            publish(settings.loadSelection())
            SetupResult.Saved
        }

    // No catch-up first: by now the next account may be the active one, and the old account's selection must not
    // be written to its cache.
    override suspend fun clear(): Boolean =
        locked(catchUpFirst = false) {
            lastListed = null
            val cleared = writer.clearLibraries()
            passes.restart()
            publish(settings.loadSelection())
            cleared
        }

    // --- The add pass -----------------------------------------------------------------------------------------------

    /**
     * Lands the add of [id] under [stamp] once its read has ended and proved it (the add pass calls it): the library
     * is saved as chosen under a new seq, with the repair of today that the change makes, then landed in the cache,
     * then published. While the selection is gone, the landed library becomes the whole choice, and the gone ones
     * leave the cache (held while a programme of theirs is on air).
     */
    override suspend fun passLanded(
        account: LibraryAccount,
        library: ItemId,
        stamp: Long,
    ): LandResult =
        locked {
            if (activeAccount() != account) return@locked LandResult.NotCurrent
            landOnce(account, library, stamp) ?: landOnce(account, library, stamp) ?: LandResult.Retry
        }

    @Suppress("ReturnCount") // Each early return is a reason nothing is landed.
    private suspend fun landOnce(
        account: LibraryAccount,
        id: ItemId,
        stamp: Long,
    ): LandResult? {
        val stored = settings.loadSelection() ?: return LandResult.NotCurrent
        if (stored.adding[id] != stamp) return LandResult.NotCurrent
        val row = library.storedLibraries(account).firstOrNull { it.id == id }
        // The walk's proof is the cache's: without it the read did not end, and the library is not chosen yet.
        if (row?.addStamp != stamp || row.walkedStamp != stamp) return LandResult.Unproven
        val after = if (stored.gone) setOf(id) else stored.chosen + id
        val next = { seq: Long ->
            stored.copy(chosen = after, seq = seq, adding = stored.adding - id, gone = false).withMissingKept()
        }
        // The repair reads the libraries afresh: no shared snapshot may still leave the added one out.
        source.evictSnapshotsNaming(account, id)
        val saved =
            when (val outcome = allocate(account, stored, after, next)) {
                Allocated.Stale -> return null
                Allocated.Failed -> return LandResult.Retry
                is Allocated.Saved -> next(outcome.seq)
            }
        room {
            if (!library.landAdd(account, id, stamp)) {
                // Lost a race in the cache: branch it again, so a chosen library never keeps a stamp.
                branches(account, setOf(id), library.storedLibraries(account))
            }
            if (stored.gone) applyRoom(account, saved, library.storedLibraries(account))
        }
        publish(saved)
        return LandResult.Landed
    }

    /**
     * The library set of the first sync (it replaces the engine's own write): under the lock, the chosen libraries
     * are branched (a stamped one landed or set back), and the cache keeps the chosen and the adding libraries, each
     * add with its stamp. The result is the chosen libraries, which the first sync reads. The lock is held only for
     * this, never for the sync.
     */
    override suspend fun firstSyncLibraries(account: LibraryAccount): Set<ItemId> =
        locked {
            val stored = settings.loadSelection() ?: return@locked emptySet()
            branches(account, stored.chosen, library.storedLibraries(account))
            applyRoom(account, stored, library.storedLibraries(account))
            stored.chosen
        }

    /** Puts the library cache in line with the stored selection (the add pass calls it after a refused write). */
    override suspend fun reconcile() {
        locked(catchUpFirst = false) { catchUp() }
    }

    // --- The server's listing ---------------------------------------------------------------------------------------

    /**
     * Follows one listing of the server's libraries (the two-read rule). A failed listing, an empty one, and one of an
     * account that is not the signed-in one change nothing. Otherwise it counts: a chosen or adding library it lacks
     * has its first miss noted, and one it lists has it cleared. A library is confirmed missing when a later counted
     * listing lacks it too, at least [SelectionConfig.missingConfirmAfter] after the first miss: an add leaves the
     * adds, and a chosen library is unticked, unless that would leave none, when the selection is gone instead (the
     * choice, the cache and the holds kept). A listed chosen library ends gone.
     */
    override suspend fun followServer(
        account: LibraryAccount,
        listed: ServerResult<List<Library>>,
    ) {
        val libraries = (listed as? ServerResult.Success)?.value ?: return
        // An empty listing while libraries are chosen is more likely a server fault: it does not count.
        if (libraries.isEmpty()) return
        locked {
            if (activeAccount() == account) {
                val ids = libraries.mapTo(HashSet()) { it.id }
                lastListed = ids
                settings.loadSelection()?.let { follow(account, it, ids) }
            }
        }
    }

    private suspend fun follow(
        account: LibraryAccount,
        stored: StoredSelection,
        ids: Set<ItemId>,
    ) {
        val now = nowMs()
        val listings = stored.listings + 1
        // A miss from the future (the clock went back) is counted again from now.
        val missing = stored.missing.filterValues { it.sinceMs <= now }.toMutableMap()
        for (id in stored.chosen + stored.adding.keys) {
            if (id in ids) missing.remove(id) else missing.putIfAbsent(id, Missing(now, listings))
        }
        val after = config.missingConfirmAfter.inWholeMilliseconds
        val confirmed = missing.filterValues { listings > it.sincePass && now - it.sinceMs >= after }.keys
        val left = stored.chosen - confirmed
        val gone = left.isEmpty() || (stored.gone && stored.chosen.none { it in ids })
        val chosen = left.ifEmpty { stored.chosen }
        val adding = stored.adding - confirmed
        val counted = stored.copy(missing = missing, listings = listings)
        if (chosen == stored.chosen && adding == stored.adding && gone == stored.gone) {
            // Bookkeeping only: the same seq, as nothing that plays changes.
            if (write(counted)) publish(counted)
            return
        }
        val next = { seq: Long ->
            counted.copy(chosen = chosen, seq = seq, adding = adding, gone = gone).withMissingKept()
        }
        val outcome = allocate(account, stored, chosen.takeIf { it != stored.chosen }, next)
        if (outcome is Allocated.Saved) afterSave(account, stored, next(outcome.seq))
    }

    // --- Resume -----------------------------------------------------------------------------------------------------

    /**
     * Run once at start, before any other operation (each waits for it): warms the stored selection, raises its seq
     * above the cache's stamps, and puts the cache in line with it, which repairs whatever a crash left between a
     * save and its cache write. It always completes [await], and a failure is reported; the next operation then puts
     * the cache in line first.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever fails, the resume completes and the next operation catches up.
    override suspend fun resumeAtStart() {
        // A call from inside the selection is a bug, not a failed resume: it is thrown as it is.
        refuseReentry()
        try {
            mutex.withLock {
                withContext(NonCancellable + InSelection) {
                    val stored = settings.loadSelection()
                    publish(stored)
                    val account = activeAccount()
                    if (stored != null && account != null) {
                        raise(account, stored)
                        reconcileRoom(account)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            roomBehind = true
            report(SelectionEvent.SelectionResumeFailed)
        } finally {
            resumed.open()
        }
    }

    // The resume's raise: a seq at or below a stamp in the cache is lifted above it (belt and braces: every
    // allocation is floored anyway).
    private suspend fun raise(
        account: LibraryAccount,
        stored: StoredSelection,
    ) {
        val top = maxStamp(library.storedLibraries(account))
        if (top < stored.seq) return
        val raised =
            when (val floor = service.floorRecord(account)) {
                RecordReset.Failed -> {
                    false
                }

                is RecordReset.Floor -> {
                    if (floor.record) {
                        // Through the repairer, and only over a lineup it may write.
                        service.loaded(account)?.writable == true &&
                            repairer.saveWithoutRepair(account, maxOf(top, floor.seq) + 1) { seq ->
                                write(stored.copy(seq = seq))
                            } is RepairOutcome.Saved
                    } else {
                        // No record this build could show: a plain save, which over a newer app's file also hides
                        // that file's repairs from any build that reads the reset seq (D17). There it skips a seq,
                        // so a newer build's hidden repair at the next one stays below the reset seq.
                        val seq = maxOf(stored.seq, top) + 1 + if (floor.newer) 1 else 0
                        commit(seq, stored.copy(seq = seq, resetSeq = if (floor.newer) seq else stored.resetSeq))
                    }
                }
            }
        if (raised) publish(settings.loadSelection()) else report(SelectionEvent.RaiseSkipped)
    }

    // Puts the cache in line, outside the resume: a failure is reported and leaves it behind for the next operation.
    @Suppress("TooGenericExceptionCaught") // Whatever the cache throws, the operation goes on from the settings.
    private suspend fun catchUp() {
        try {
            activeAccount()?.let { reconcileRoom(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            report(SelectionEvent.RoomBehind)
        }
    }

    // Resume step 3: the chosen libraries branched, the adds already read with no stamp dropped (with a new seq), the
    // cache written from the stored selection, and the passes and the first sync asked for when there is work.
    private suspend fun reconcileRoom(account: LibraryAccount) {
        val first = settings.loadSelection() ?: return
        var changed = branches(account, first.chosen, library.storedLibraries(account))
        val rows = library.storedLibraries(account).associateBy { it.id }
        val dropped =
            first.adding.keys.filterTo(HashSet()) { id ->
                val row = rows[id]
                row != null && row.fetched && row.addStamp == null && row.heldUntilMs == null
            }
        if (dropped.isNotEmpty()) {
            // Only a downgrade or a bug leaves one: dropping it ends a restart loop. Not committed: left out below.
            report(SelectionEvent.AddDropped)
            val next = { seq: Long -> first.copy(seq = seq, adding = first.adding - dropped).withMissingKept() }
            if (allocate(account, first, null, next) is Allocated.Saved) changed = true
        }
        val stored = settings.loadSelection() ?: return
        applyRoom(account, stored, library.storedLibraries(account))
        roomBehind = false
        if (stored.adding.isNotEmpty()) passes.restart()
        // A setup the process did not see to its end: the first sync was never asked for.
        if (library.storedLibraries(account).any { it.id in stored.chosen && it.isFreshPending() }) {
            firstSync.ensureRunning()
        }
        if (changed) publish(stored)
    }

    // A chosen library not read yet and not marked for a rewalk: the first sync's to read.
    private fun StoredLibrary.isFreshPending(): Boolean {
        val unread = !fetched && !rewalkPending
        return unread && addStamp == null && heldUntilMs == null
    }

    // --- Shared steps -----------------------------------------------------------------------------------------------

    // The two branches of a stamped chosen row: a proven one lands, an unproven one is set back, and stay current is
    // asked to read it again. True when either changed the cache.
    private suspend fun branches(
        account: LibraryAccount,
        ids: Set<ItemId>,
        rows: List<StoredLibrary>,
    ): Boolean {
        var changed = false
        for (row in rows) {
            val stamp = row.addStamp
            if (row.id !in ids || stamp == null || row.heldUntilMs != null) continue
            changed = branch(account, row.id, stamp, proven = row.walkedStamp == stamp) || changed
        }
        return changed
    }

    // One stamped chosen row: landed when proven, else set back. A set-back that loses its compare-and-set to the add
    // walk's proof (the add pass does not hold this lock) reads the row again and lands it when it is proven now.
    private suspend fun branch(
        account: LibraryAccount,
        id: ItemId,
        stamp: Long,
        proven: Boolean,
    ): Boolean =
        when {
            proven -> {
                library.landAdd(account, id, stamp)
            }

            library.revertUnproven(account, id, stamp) -> {
                report(SelectionEvent.UnprovenReverted)
                stayCurrent.request()
                true
            }

            else -> {
                val now = library.storedLibraries(account).firstOrNull { it.id == id }
                val provenNow = now?.addStamp == stamp && now.walkedStamp == stamp && now.heldUntilMs == null
                provenNow && library.landAdd(account, id, stamp)
            }
        }

    // After a save that changed the choice: the cache follows it, the passes restart when the adds changed, and the
    // new selection is published.
    private suspend fun afterSave(
        account: LibraryAccount,
        stored: StoredSelection,
        saved: StoredSelection,
    ) {
        room { applyRoom(account, saved, library.storedLibraries(account)) }
        if (saved.adding.keys != stored.adding.keys) passes.restart()
        publish(saved)
    }

    // The cache write of a selection: the chosen and the adding libraries kept, every add with its stamp, and the
    // holds of the pins on air. The cache refuses a new stamp on a row with neither a hold nor a stamp (one set back
    // to be read again, or one whose untick never reached the cache), so such a row is held first, in a write of its
    // own, and then stamped: no add is ever dropped or left unstamped for that (D1).
    private suspend fun applyRoom(
        account: LibraryAccount,
        saved: StoredSelection,
        rows: List<StoredLibrary>,
    ) {
        val now = nowMs()
        val keep = saved.chosen + saved.adding.keys
        val holds = holdsFor(account, rows, now)
        val byId = rows.associateBy { it.id }
        val refused = saved.adding.keys.filterTo(HashSet()) { byId[it]?.refusesAStamp() == true }
        if (refused.isNotEmpty()) {
            report(SelectionEvent.HeldBeforeStamp)
            val until = now + repairer.pauseHold.inWholeMilliseconds
            val first = holds + refused.associateWith { maxOf(holds[it] ?: until, until) }
            library.applySelection(account, keep - refused, saved.adding - refused, first, now)
        }
        library.applySelection(account, keep, saved.adding, holds, now)
    }

    // The cache's rule (LibraryDao.applySelection): a new stamp only for no row, or a row with a hold or a stamp.
    private fun StoredLibrary.refusesAStamp(): Boolean = heldUntilMs == null && addStamp == null

    // The holds of the libraries pinned on air, each never below the hold its row already has.
    private suspend fun holdsFor(
        account: LibraryAccount,
        rows: List<StoredLibrary>,
        now: Long,
    ): Map<ItemId, Long> {
        val existing = rows.associate { it.id to it.heldUntilMs }
        return repairer.heldPins(account, now).mapValues { (id, until) -> maxOf(until, existing[id] ?: until) }
    }

    // How a seq-moving save went.
    private sealed interface Allocated {
        data class Saved(
            val seq: Long,
        ) : Allocated

        data object Stale : Allocated

        data object Failed : Allocated
    }

    // A save that moves the seq, through the repairer: with today's repair for the change to [after] when given, else
    // with none. Before any lineup exists there is nothing to repair, and it is a plain save.
    private suspend fun allocate(
        account: LibraryAccount,
        stored: StoredSelection,
        after: Set<ItemId>?,
        next: (Long) -> StoredSelection,
    ): Allocated {
        val atLeast = floorOf(library.storedLibraries(account))
        val save: suspend (Long) -> Boolean = { seq -> write(next(seq)) }
        val allocated =
            if (after != null) {
                allocated(repairer.selectionChanged(account, after, nowMs(), atLeast, save)) { it.seq }
            } else {
                allocated(repairer.saveWithoutRepair(account, atLeast, save)) { it }
            }
        // Null: no lineup, so nothing to repair.
        return allocated ?: plain(account, stored, atLeast, next)
    }

    // A repairer outcome as an allocation, with [seqOf] its saved seq; null for no lineup.
    private inline fun <T> allocated(
        outcome: RepairOutcome<T>,
        seqOf: (T) -> Long,
    ): Allocated? =
        when (outcome) {
            is RepairOutcome.Saved -> Allocated.Saved(seqOf(outcome.result))

            RepairOutcome.Stale -> Allocated.Stale

            RepairOutcome.NoLineup -> null

            RepairOutcome.Refused,
            RepairOutcome.Unsaved,
            RepairOutcome.UndoFailed,
            RepairOutcome.SelectionUnknown,
            RepairOutcome.AlreadySaved,
            -> Allocated.Failed
        }

    // A save with no lineup to repair: only when the disk holds no record this build could show (none yet, one set
    // aside, or a newer app's, whose repairs the reset seq then hides). One above the stored seq and [atLeast].
    private suspend fun plain(
        account: LibraryAccount,
        stored: StoredSelection,
        atLeast: Long,
        next: (Long) -> StoredSelection,
    ): Allocated {
        val floor = service.floorRecord(account) as? RecordReset.Floor
        if (floor == null || floor.record) return Allocated.Failed
        // Over a newer app's file, one seq is skipped, as in the resume's raise (D17).
        val seq = maxOf(stored.seq + 1, atLeast, floor.seq + 1) + if (floor.newer) 1 else 0
        val selection = next(seq).let { if (floor.newer) it.copy(resetSeq = seq) else it }
        return if (commit(seq, selection)) Allocated.Saved(seq) else Allocated.Failed
    }

    // One above the highest add stamp in the cache: no seq may equal a stamp left there.
    private fun floorOf(rows: List<StoredLibrary>): Long = maxStamp(rows) + 1

    private fun maxStamp(rows: List<StoredLibrary>): Long = rows.maxOfOrNull { it.addStamp ?: 0L } ?: 0L

    // One write of the selection. The settings never throw by contract; a write that did is read back by the caller.
    @Suppress("TooGenericExceptionCaught") // Whatever a write throws, it is not known to be stored.
    private suspend fun write(selection: StoredSelection): Boolean =
        try {
            writer.saveSelection(selection)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    // A direct save of [seq]: committed when it returned true, or when the stored seq reads as [seq] after it.
    private suspend fun commit(
        seq: Long,
        selection: StoredSelection,
    ): Boolean = write(selection) || settings.loadSelection()?.seq == seq

    // A cache write after a committed save: a failure is reported, and the next operation catches up (D13).
    @Suppress("TooGenericExceptionCaught") // Whatever the cache throws, the save stands.
    private suspend fun room(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            roomBehind = true
            report(SelectionEvent.RoomBehind)
        }
    }

    // A step after a committed setup save (D6): a failure is reported and does not undo or fail the setup.
    @Suppress("TooGenericExceptionCaught") // Whatever the step throws, the setup is saved.
    private inline fun followUp(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            report(SelectionEvent.SetupFollowUpFailed)
        }
    }

    // Publishes the selection to features, and its seq to the refresher (never back).
    private fun publish(stored: StoredSelection?) {
        view.value = stored?.let { ChoiceView(it.chosen, it.adding.keys, it.gone) }
        if (stored != null) seqs.value = maxOf(seqs.value, stored.seq)
    }

    // A miss is kept only for a library still chosen or being added.
    private fun StoredSelection.withMissingKept(): StoredSelection {
        val kept = missing.filterKeys { it in chosen || it in adding }
        return copy(missing = kept)
    }
}
