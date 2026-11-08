// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.FirstSync
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.Missing
import tv.stationbreak.core.domain.SelectionStore
import tv.stationbreak.core.domain.SelectionWriter
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.StayCurrentRequests
import tv.stationbreak.core.domain.StoredLibrary
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.repair.TodayRepairer
import tv.stationbreak.core.domain.lineup.repair.refuseInTurn
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.Session
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.TimeSource

/** What the selection reports, by name only: never an ID, a name or a count that could tell one. */
enum class SelectionEvent {
    /** The resume at start failed; the next operation puts the library cache in line first. */
    SelectionResumeFailed,

    /** A cache write failed after the selection was saved: the next operation puts the cache in line first. */
    RoomBehind,

    /** An add whose read was never proven to end was set back to be read again. */
    UnprovenReverted,

    /** No account is active in the library cache, so nothing was stored (a sign-in activates it, as a restore does). */
    NoActiveAccount,

    /** A stored choice is another account's (a sign-in's clear failed): it is not changed, followed or reconciled. */
    ForeignChoice,

    /** The choice could not be dropped (a sign-in or a switch of user goes on; the picker's Done clears again). */
    ClearFailed,

    /** A row the cache would refuse a new stamp was held first, then stamped. */
    HeldBeforeStamp,

    /** A step after a committed setup save failed (the add pass's restart, or the first sync's request). */
    SetupFollowUpFailed,

    /** The seq could not be raised above the cache's stamps at start; the next operation's floor covers it. */
    RaiseSkipped,

    /** The add pass could not be asked for after a committed write (the write stands; the next start asks again). */
    RestartFailed,
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
 * through [SelectionResume]. Its writes reach the settings through [SelectionWriter] ([store]) alone. [resumed] is the
 * gate the app binds as [SelectionReady]: the resume opens it, and the selection waits on it; the selection is not a
 * [SelectionReady] itself, so nothing that waits can open it.
 */
@Suppress("LongParameterList", "TooManyFunctions") // The stores and ports it writes, one for one; one per step.
class LibrarySelection(
    private val store: SelectionStore,
    private val library: LibraryRepository,
    private val server: MediaServer,
    private val session: () -> Session?,
    repairer: TodayRepairer,
    service: LineupService,
    private val source: BuiltInScheduleSource,
    private val passes: LibraryPasses,
    private val firstSync: FirstSync,
    stayCurrent: StayCurrentRequests,
    private val resumed: ResumeGate,
    private val config: SelectionConfig = SelectionConfig(),
    private val clock: Clock = Clock.System,
    private val resumeTook: (Long) -> Unit = {},
    private val report: (SelectionEvent) -> Unit = {},
) : LibraryChoices,
    LibrarySetup,
    AddLandings,
    FirstSyncLibraries,
    ServerListings,
    SelectionResume {
    private val mutex = Mutex()
    private val ownership = SelectionOwnership(library)
    private val allocation = SelectionAllocation(store, library, repairer, service, clock)
    private val cache = SelectionRoom(library, repairer, stayCurrent, clock, report)

    // The libraries the last counted listing gave: a library may be added only if it was listed.
    @Volatile private var lastListed: Set<ItemId>? = null

    override val selection: StateFlow<ChoiceView?> = ownership.selection

    /**
     * The seq of the stored selection (D14, level-based): it moves with every save that changes what plays, and only
     * once that save (and its cache write) is done. The lineup refresher follows it.
     */
    val selectionChanges: StateFlow<Long> = ownership.selectionChanges

    // --- Entry ------------------------------------------------------------------------------------------------------

    // Refused from inside a repairer turn or this lock; waits for the resume; then under the lock, to its end, with
    // the cache put in line first when an earlier write left it behind.
    // [fromAddPass]: the add pass is the caller, inside its own worker, so the catch-up does not ask for the add pass
    // (that would replace the very worker that called): the worker reads the adds again itself.
    private suspend fun <T> locked(
        catchUpFirst: Boolean = true,
        fromAddPass: Boolean = false,
        block: suspend () -> T,
    ): T {
        refuseReentry()
        resumed.await()
        return mutex.withLock {
            withContext(NonCancellable + InSelection) {
                if (catchUpFirst && cache.roomBehind) catchUp(restartPasses = !fromAddPass)
                block()
            }
        }
    }

    private suspend fun refuseReentry() {
        refuseInTurn()
        check(currentCoroutineContext()[InSelection] == null) { "Entered from inside the selection's own lock" }
    }

    private suspend fun activeAccount(): LibraryAccount? = ownership.activeAccount()

    // The active account, or null reported: a change or a setup with none stores nothing.
    private suspend fun activeAccountOrReport(): LibraryAccount? =
        activeAccount().also { if (it == null) report(SelectionEvent.NoActiveAccount) }

    private fun nowMs(): Long = clock.now().toEpochMilliseconds()

    // --- LibraryChoices ---------------------------------------------------------------------------------------------

    override suspend fun listing(): LibraryListing {
        refuseReentry()
        val held = session() ?: return LibraryListing.Unavailable(null, selection.value)
        val listed = server.libraries(held)
        followServer(LibraryAccount(held.server.id, held.userId), listed)
        return when (listed) {
            is ServerResult.Success -> LibraryListing.Listed(listed.value, selection.value)
            is ServerResult.Failure -> LibraryListing.Unavailable(listed.error, selection.value)
        }
    }

    override suspend fun change(ticked: Set<ItemId>): ChangeResult =
        locked {
            val account = activeAccountOrReport() ?: return@locked ChangeResult.Failed
            // Stale (the lineup or its repairs moved while the repair ran): once more, from a fresh read.
            changeOnce(account, ticked) ?: changeOnce(account, ticked) ?: ChangeResult.Failed
        }

    // One attempt at a change; null when it was stale.
    @Suppress("ReturnCount") // Each early return is a reason nothing is stored.
    private suspend fun changeOnce(
        account: LibraryAccount,
        ticked: Set<ItemId>,
    ): ChangeResult? {
        val stored = store.loadSelection() ?: return ChangeResult.Failed
        // Another account's choice (a sign-in whose clear failed) is not this account's to change.
        if (!ownership.owns(account, stored)) return ChangeResult.Failed.also { report(SelectionEvent.ForeignChoice) }
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
            when (val outcome = allocation.allocate(account, stored, repaired, next)) {
                Allocated.Stale -> return null
                is Allocated.Failed -> return ChangeResult.Failed
                is Allocated.Saved -> next(outcome.seq)
            }
        afterSave(account, stored, saved)
        return ChangeResult.Saved
    }

    // --- LibrarySetup -----------------------------------------------------------------------------------------------

    // No catch-up first: a choice still stored may be the last account's, and must not reach this one's cache.
    @Suppress("ReturnCount") // Each early return is a reason nothing is stored.
    override suspend fun choose(ids: Set<ItemId>): SetupResult =
        locked(catchUpFirst = false) {
            if (ids.isEmpty()) return@locked SetupResult.Failed
            val account = activeAccountOrReport() ?: return@locked SetupResult.Failed
            val held = store.loadSelection()
            if (held != null) {
                // A setup follows a clear (every sign-in path clears first): it never writes over this account's
                // choice; one still stored is a clear that failed, and the caller clears again and asks once more.
                if (ownership.owns(account, held)) return@locked SetupResult.ChoiceStillStored
                // Another account's choice (its clear failed before this sign-in): not this one's, so it goes here.
                if (!store.clearLibraries()) {
                    report(SelectionEvent.ClearFailed)
                    return@locked SetupResult.Failed
                }
            }
            // A listing of the last account's server says nothing about this choice.
            lastListed = null
            if (!allocation.reset(account, ids)) return@locked SetupResult.Failed
            // An add under way was of the old choice. The save has committed: what follows cannot fail the setup.
            if (held?.adding.orEmpty().isNotEmpty()) followUp(SelectionEvent.SetupFollowUpFailed) { passes.restart() }
            cache.room {
                cache.branches(account, ids, library.storedLibraries(account))
                // The cache follows the new choice now, not only when the first sync reads its set: a stay-current
                // pass before that run meets every chosen library's row, and none of a library the setup dropped.
                store.loadSelection()?.takeIf { ownership.owns(account, it) }?.let {
                    cache.applyRoom(account, it, library.storedLibraries(account))
                }
            }
            // The first sync reads the new libraries, replacing any run still going: one running now read the choice
            // this setup replaced (Task 4.4b, R1). On the same account, its first sync long finished, nothing else
            // would read them. Queued after the save, under this lock, so its library set is the new choice's.
            followUp(SelectionEvent.SetupFollowUpFailed) { firstSync.retry() }
            ownership.publishOwned(store.loadSelection())
            SetupResult.Saved
        }

    // No catch-up first: by now the next account may be the active one, and the old account's selection must not
    // be written to its cache.
    override suspend fun clear(): Boolean =
        locked(catchUpFirst = false) {
            lastListed = null
            val adding = store.loadSelection()?.adding.orEmpty()
            val cleared = store.clearLibraries()
            // The caller goes on (a sign-in, a switch of user); the picker's Done clears again before its setup.
            if (!cleared) report(SelectionEvent.ClearFailed)
            // Only an add under way has a worker to stop.
            if (adding.isNotEmpty()) restartPasses()
            ownership.publishOwned(store.loadSelection())
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
        id: ItemId,
        stamp: Long,
    ): LandResult =
        locked(fromAddPass = true) {
            if (activeAccount() != account) return@locked LandResult.NotCurrent
            landOnce(account, id, stamp) ?: landOnce(account, id, stamp) ?: LandResult.Retry
        }

    @Suppress("ReturnCount") // Each early return is a reason nothing is landed.
    private suspend fun landOnce(
        account: LibraryAccount,
        id: ItemId,
        stamp: Long,
    ): LandResult? {
        val stored = store.loadSelection() ?: return LandResult.NotCurrent
        if (stored.adding[id] != stamp || !ownership.owns(account, stored)) return LandResult.NotCurrent
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
            when (val outcome = allocation.allocate(account, stored, after, next)) {
                Allocated.Stale -> return null
                is Allocated.Failed -> return if (outcome.terminal) LandResult.Failed else LandResult.Retry
                is Allocated.Saved -> next(outcome.seq)
            }
        cache.room {
            if (!library.landAdd(account, id, stamp)) {
                // Lost a race in the cache: branch it again, so a chosen library never keeps a stamp.
                cache.branches(account, setOf(id), library.storedLibraries(account))
            }
            if (stored.gone) cache.applyRoom(account, saved, library.storedLibraries(account))
        }
        ownership.publish(saved)
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
            val stored = store.loadSelection()?.takeIf { ownership.owns(account, it) } ?: return@locked emptySet()
            cache.branches(account, stored.chosen, library.storedLibraries(account))
            cache.applyRoom(account, stored, library.storedLibraries(account))
            stored.chosen
        }

    /**
     * The libraries being added for [account], each with its stamp: empty when [account] is not the active account or
     * the stored choice is not its own. The add pass reads its work from this alone.
     */
    override suspend fun pendingAdds(account: LibraryAccount): Map<ItemId, Long> =
        locked(fromAddPass = true) {
            if (activeAccount() != account) return@locked emptyMap()
            store
                .loadSelection()
                ?.takeIf { ownership.owns(account, it) }
                ?.adding
                .orEmpty()
        }

    /**
     * Puts the library cache in line with the stored selection (the add pass calls it after a refused write). It does
     * not ask for the add pass: the caller is that pass, which reads the adds again itself.
     */
    override suspend fun reconcile() {
        locked(catchUpFirst = false, fromAddPass = true) { catchUp(restartPasses = false) }
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
                // Another account's choice is not followed for this one.
                store.loadSelection()?.takeIf { ownership.owns(account, it) }?.let { follow(account, it, ids) }
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
            if (allocation.write(counted, account)) ownership.publish(counted)
            return
        }
        val next = { seq: Long ->
            counted.copy(chosen = chosen, seq = seq, adding = adding, gone = gone).withMissingKept()
        }
        val outcome = allocation.allocate(account, stored, chosen.takeIf { it != stored.chosen }, next)
        if (outcome is Allocated.Saved) afterSave(account, stored, next(outcome.seq))
    }

    // --- Resume -----------------------------------------------------------------------------------------------------

    /**
     * Run once at start, before any other operation (each waits for the gate it opens): warms the stored selection,
     * raises its seq above the cache's stamps, and puts the cache in line with it, which repairs whatever a crash left
     * between a save and its cache write. It always opens [resumed], however it ends; a failure is reported, and the
     * next operation then puts the cache in line first. A call from inside the selection is thrown as it is.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever fails, the resume completes and the next operation catches up.
    override suspend fun resumeAtStart() {
        // A call from inside the selection is a bug, not a failed resume: it is thrown as it is.
        refuseReentry()
        val began = TimeSource.Monotonic.markNow()
        try {
            mutex.withLock {
                withContext(NonCancellable + InSelection) {
                    val stored = store.loadSelection()
                    val account = activeAccount()
                    // An older record names no owner: the active account adopts it (for this process even if the
                    // write fails), and the same seq is saved with it. With no account active it is left alone.
                    val current =
                        if (stored != null && stored.owner == null && account != null) {
                            ownership.adopted = account
                            allocation.write(stored, account)
                            store.loadSelection()
                        } else {
                            stored
                        }
                    if (current != null && account != null && current.isOtherThan(account)) {
                        // Another account's choice: a sign-in's clear failed, then the process died. The clear is
                        // made now, as that sign-in meant, so the launch routes to the picker and not to a first
                        // sync that has nothing of this account's to read.
                        if (!store.clearLibraries()) report(SelectionEvent.ClearFailed)
                        ownership.publish(null)
                    } else {
                        ownership.publishOwned(current, account)
                        // A reload that read nothing skips the raise and the reconcile: the next operation catches
                        // up.
                        if (current != null && account != null && ownership.owns(account, current)) {
                            raise(account, current)
                            reconcileRoom(account)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            cache.roomBehind = true
            report(SelectionEvent.SelectionResumeFailed)
        } finally {
            resumed.open()
            resumeTook(began.elapsedNow().inWholeMilliseconds)
        }
    }

    // The resume's raise: a seq at or below a stamp in the cache is lifted above it (belt and braces: every
    // allocation is floored anyway).
    private suspend fun raise(
        account: LibraryAccount,
        stored: StoredSelection,
    ) {
        val top = allocation.maxStamp(library.storedLibraries(account))
        if (top < stored.seq) return
        val raised = allocation.raise(account, stored, top)
        if (raised) ownership.publish(store.loadSelection()) else report(SelectionEvent.RaiseSkipped)
    }

    // Puts the cache in line, outside the resume: a failure is reported and leaves it behind for the next operation.
    @Suppress("TooGenericExceptionCaught") // Whatever the cache throws, the operation goes on from the settings.
    private suspend fun catchUp(restartPasses: Boolean) {
        try {
            activeAccount()?.let { reconcileRoom(it, restartPasses) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            report(SelectionEvent.RoomBehind)
        }
    }

    // Resume step 3: the chosen libraries branched, the cache written from the stored selection, and the passes and
    // the first sync asked for when there is work. An add whose row the cache would refuse a stamp (one read with no
    // stamp, whose untick never reached the cache, or one set back) is not dropped from the adds: the cache write
    // holds it first and then stamps it, so the viewer's tick stands and nothing loops. [restartPasses] is false when
    // the add pass itself is the caller.
    private suspend fun reconcileRoom(
        account: LibraryAccount,
        restartPasses: Boolean = true,
    ) {
        val stored = store.loadSelection()
        if (stored == null) {
            // Nothing chosen: nothing to put in line.
            cache.roomBehind = false
            return
        }
        // Another account's choice never reaches this account's cache; it stays behind until a setup replaces it. There
        // is nothing of it to put in line, so it is not looked at again on every call (a slow TV).
        if (!ownership.owns(account, stored)) {
            cache.roomBehind = false
            return
        }
        val changed = cache.branches(account, stored.chosen, library.storedLibraries(account))
        cache.applyRoom(account, stored, library.storedLibraries(account))
        cache.roomBehind = false
        // The cache is written: a restart that throws now must not skip the first sync's request below (D6).
        if (stored.adding.isNotEmpty() && restartPasses) restartPasses()
        // A setup the process did not see to its end: the first sync was never asked for.
        if (library.storedLibraries(account).any { it.id in stored.chosen && it.isFreshPending() }) {
            firstSync.ensureRunning()
        }
        if (changed) ownership.publish(stored)
    }

    // A chosen library not read yet and not marked for a rewalk: the first sync's to read.
    private fun StoredLibrary.isFreshPending(): Boolean {
        val unread = !fetched && !rewalkPending
        return unread && addStamp == null && heldUntilMs == null
    }

    // --- Shared steps -----------------------------------------------------------------------------------------------

    // After a save that changed the choice: the cache follows it, the passes restart when the adds changed, and the
    // new selection is published.
    private suspend fun afterSave(
        account: LibraryAccount,
        stored: StoredSelection,
        saved: StoredSelection,
    ) {
        cache.room { cache.applyRoom(account, saved, library.storedLibraries(account)) }
        if (saved.adding.keys != stored.adding.keys) restartPasses()
        ownership.publish(saved)
    }

    // A step after a committed write (D6): a failure is reported as [event] and does not undo or fail the write, nor
    // skip what follows it.
    @Suppress("TooGenericExceptionCaught") // Whatever the step throws, the write is saved.
    private inline fun followUp(
        event: SelectionEvent,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            report(event)
        }
    }

    // Asks for the add pass after a committed write; a request that throws is reported (the next start asks again).
    private fun restartPasses() = followUp(SelectionEvent.RestartFailed) { passes.restart() }

    // A miss is kept only for a library still chosen or being added.
    private fun StoredSelection.withMissingKept(): StoredSelection {
        val kept = missing.filterKeys { it in chosen || it in adding }
        return copy(missing = kept)
    }
}
