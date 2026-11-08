// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.CancellationException
import tv.stationbreak.core.domain.FirstSync
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.SelectionStore
import tv.stationbreak.core.domain.StoredLibrary
import tv.stationbreak.core.domain.StoredSelection

/**
 * The resume and the catch-up of the selection (spec 26, Part 4 §2): at start the stored selection is warmed, an older
 * build's record adopted, its seq raised and the cache put in line; later, an operation that finds the cache behind
 * puts it in line first. Also the steps after a committed save: the cache following it, the add pass and the first
 * sync asked for, and the new selection published.
 *
 * Part of [LibrarySelection], which builds it: it takes no lock of its own and runs only inside the selection's lock
 * (`locked {}` or the resume).
 */
@Suppress("LongParameterList") // The stores and the collaborators it puts in line, one for one.
internal class SelectionReconcile(
    private val store: SelectionStore,
    private val library: LibraryRepository,
    private val passes: LibraryPasses,
    private val firstSync: FirstSync,
    private val ownership: SelectionOwnership,
    private val allocation: SelectionAllocation,
    private val cache: SelectionRoom,
    private val report: (SelectionEvent) -> Unit,
) {
    // The resume's work under the lock (see [LibrarySelection.resumeAtStart]). What it throws is the caller's to
    // report.
    suspend fun resume() {
        val stored = store.loadSelection()
        val account = ownership.activeAccount()
        // An older record names no owner: the active account adopts it (for this process even if the write fails),
        // and the same seq is saved with it. With no account active it is left alone.
        val current =
            if (stored != null && stored.owner == null && account != null) {
                ownership.adopted = account
                allocation.write(stored, account)
                store.loadSelection()
            } else {
                stored
            }
        if (current != null && account != null && current.isOtherThan(account)) {
            // Another account's choice: a sign-in's clear failed, then the process died. The clear is made now, as
            // that sign-in meant, so the launch routes to the picker and not to a first sync that has nothing of this
            // account's to read.
            if (!store.clearLibraries()) report(SelectionEvent.ClearFailed)
            ownership.publish(null)
        } else {
            ownership.publishOwned(current, account)
            // A reload that read nothing skips the raise and the reconcile: the next operation catches up.
            if (current != null && account != null && ownership.owns(account, current)) {
                raise(account, current)
                reconcileRoom(account)
            }
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
    suspend fun catchUp(restartPasses: Boolean) {
        try {
            ownership.activeAccount()?.let { reconcileRoom(it, restartPasses) }
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

    // After a save that changed the choice: the cache follows it, the passes restart when the adds changed, and the
    // new selection is published.
    suspend fun afterSave(
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
    fun followUp(
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
    fun restartPasses() = followUp(SelectionEvent.RestartFailed) { passes.restart() }
}
