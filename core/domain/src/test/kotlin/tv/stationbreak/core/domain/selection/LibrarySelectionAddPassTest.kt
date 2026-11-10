// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.lineupOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The add pass's side of the selection (spec 26, Part 4 §2, Task 4.4b): what the add pass calls never asks for the
 * add pass (it would replace the very worker that called it), a landing that cannot succeed by trying again says so,
 * a choice of another account is put in line once, and a restart that throws after a committed write is reported and
 * never fails the write nor skips what follows it.
 */
internal class LibrarySelectionAddPassTest : SelectionTestBase() {
    // A chosen and B being added, its stamp in the cache and its walk proved.
    private fun adding(): Pair<Tv, Long> {
        val tv = Tv(lineup = lineupOf(laughs), chosen = setOf(libA)).ready(setOf(libA, libB))
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB)))
        val stamp = assertNotNull(tv.stored()).adding.getValue(libB)
        tv.seedItems(comediesB)
        assertTrue(tv.fake.proveWalk(ACCOUNT, libB, stamp))
        return tv to stamp
    }

    // Binding item 1: the add pass's reconcile runs inside the add worker; a restart there would cancel the worker.
    @Test
    fun aReconcileFromTheAddPass_neverRestartsIt() {
        val (tv, _) = adding()
        val before = tv.restarts

        runBlocking { tv.selection.reconcile() }

        assertEquals(before, tv.restarts, "the worker goes on in its own process; nothing replaces it")
    }

    // Binding item 1: a landing that catches the cache up first (an earlier cache write failed) restarts nothing.
    @Test
    fun aLandingThatCatchesUpFirst_neverRestartsTheAddPass() {
        val tv = Tv(lineup = lineupOf(laughs), chosen = setOf(libA)).ready(setOf(libA, libB, libC))
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB)))
        val stamp = assertNotNull(tv.stored()).adding.getValue(libB)
        // A second tick's cache write fails after its save: the cache is behind, and the next call catches up.
        tv.library.failApplies = 1
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB, libC)))
        assertTrue(SelectionEvent.RoomBehind in tv.events)
        tv.seedItems(comediesB)
        assertTrue(tv.fake.proveWalk(ACCOUNT, libB, stamp))
        val before = tv.restarts

        assertEquals(LandResult.Landed, runBlocking { tv.selection.passLanded(ACCOUNT, libB, stamp) })

        assertEquals(before, tv.restarts)
    }

    // Fix round 1 (A2): a record that refuses may be a save that failed, which the record does not tell apart from
    // one that does not fit: it may pass, so it is a retry (the add pass's cap bounds the cost).
    @Test
    fun aLandingTheRecordRefuses_isRetry() {
        val (tv, stamp) = adding()
        tv.rig.store.inner.failWrites = true

        assertEquals(LandResult.Retry, runBlocking { tv.selection.passLanded(ACCOUNT, libB, stamp) })

        assertEquals(stamp, assertNotNull(tv.stored()).adding[libB], "nothing landed")
        tv.rig.store.inner.failWrites = false
        assertEquals(LandResult.Landed, runBlocking { tv.selection.passLanded(ACCOUNT, libB, stamp) }, "it passed")
    }

    // Binding item 2: a result that trying again in this process cannot change (the saved selection could not be read
    // when the repair asked for it) is not a retry.
    @Test
    fun aLandingWhoseSelectionCannotBeRead_isFailed_notRetry() {
        val (tv, stamp) = adding()
        // The landing reads the selection, then the repair reads it again: that second read fails.
        tv.settings.unreadableLoad = tv.settings.loads.get() + 2

        assertEquals(LandResult.Failed, runBlocking { tv.selection.passLanded(ACCOUNT, libB, stamp) })

        assertEquals(stamp, assertNotNull(tv.stored()).adding[libB], "nothing landed")
    }

    // Fix round 1 (A3): the add pass's work list is the selection's own, owner-checked.
    @Test
    fun pendingAdds_areTheAccountsOwnAdds() {
        val (tv, stamp) = adding()

        assertEquals(mapOf(libB to stamp), runBlocking { tv.selection.pendingAdds(ACCOUNT) })
        assertEquals(emptyMap(), runBlocking { tv.selection.pendingAdds(other) }, "not the active account")
    }

    @Test
    fun pendingAdds_ofAnotherAccountsChoice_areNone() {
        val tv = Tv(stored = StoredSelection(setOf(libA), seq = 2L, adding = mapOf(libB to 2L), owner = other))
        tv.resume()

        assertEquals(emptyMap(), runBlocking { tv.selection.pendingAdds(ACCOUNT) })
    }

    // Binding item 2: a save that failed may succeed next time: still a retry.
    @Test
    fun aLandingWhoseSaveFails_isStillRetry() {
        val (tv, stamp) = adding()
        tv.settings.failSaves = 1

        assertEquals(LandResult.Retry, runBlocking { tv.selection.passLanded(ACCOUNT, libB, stamp) })
    }

    // Binding item 8: another account's choice leaves nothing to put in line, so it is not looked at again each call.
    @Test
    fun aForeignChoice_isPutInLineOnce_notOnEveryCall() {
        val tv = Tv(stored = StoredSelection(setOf(libA, libB), seq = 1L, owner = other))
        tv.resume()
        runBlocking { tv.selection.firstSyncLibraries(ACCOUNT) }
        val before = tv.settings.loads.get()

        val chosen = runBlocking { tv.selection.firstSyncLibraries(ACCOUNT) }

        assertTrue(chosen.isEmpty())
        assertEquals(1, tv.settings.loads.get() - before, "its own read only: no catch-up again")
    }

    // Binding item 9: a clear that committed stands when the restart after it throws.
    @Test
    fun aClearWhoseRestartThrows_isStillAClear_andIsReported() {
        val (tv, _) = adding()
        tv.failRestart = true

        assertTrue(runBlocking { tv.selection.clear() })

        assertEquals(null, tv.stored())
        assertTrue(SelectionEvent.RestartFailed in tv.events)
    }

    // Binding item 9: the catch-up's restart throwing skips neither the first sync's request nor the cache write.
    @Test
    fun aCatchUpWhoseRestartThrows_stillAsksForTheFirstSync() {
        val tv =
            Tv(
                chosen = setOf(libA),
                stored = StoredSelection(setOf(libA, libC), seq = 2L, adding = mapOf(libB to 2L), resetSeq = 2L),
            )
        runBlocking { tv.fake.applySelection(ACCOUNT, setOf(libA, libC, libB), mapOf(libB to 2L), nowMs = atMs) }
        tv.failRestart = true

        tv.resume()

        assertEquals(1, tv.firstSync.ensureCalls, "the setup crash row still starts the first sync")
        assertTrue(SelectionEvent.RestartFailed in tv.events)
        assertTrue(SelectionEvent.SelectionResumeFailed !in tv.events)
    }

    // Binding item 9: the same for a change's restart: the change was saved, so it says Saved.
    @Test
    fun aChangeWhoseRestartThrows_isStillSaved() {
        val tv = Tv(lineup = lineupOf(laughs), chosen = setOf(libA)).ready(setOf(libA, libB))
        tv.failRestart = true

        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB)))

        assertEquals(setOf(libB), assertNotNull(tv.stored()).adding.keys)
        assertTrue(SelectionEvent.RestartFailed in tv.events)
    }

    // Issue #654 item 1: an add the add pass gave up for this run of the app is published as stalled, so a screen
    // never shows it as being added for ever. Ticked again, it is a new add under a new stamp, and not stalled.
    @Test
    fun anAddGivenUp_isPublishedAsStalled_untilTickedAgain() {
        val (tv, stamp) = adding()
        assertTrue(assertNotNull(tv.selection.selection.value).stalled.isEmpty())

        runBlocking { tv.selection.addGivenUp(ACCOUNT, libB, stamp) }

        val view = assertNotNull(tv.selection.selection.value)
        assertEquals(setOf(libB), view.stalled)
        assertEquals(setOf(libB), view.adding, "still stored as being added: the next start asks again")
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA)))
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB)))
        assertTrue(assertNotNull(tv.selection.selection.value).stalled.isEmpty())
    }

    // An older stamp, or another account, gives up nothing that is stored now.
    @Test
    fun aGiveUpOfAnOlderStampOrAnotherAccount_stallsNothing() {
        val (tv, stamp) = adding()

        runBlocking { tv.selection.addGivenUp(ACCOUNT, libB, stamp - 1) }
        runBlocking { tv.selection.addGivenUp(other, libB, stamp) }

        assertTrue(assertNotNull(tv.selection.selection.value).stalled.isEmpty())
    }

    // A give-up is the account's own: the same id and stamp stored for another account does not show as given up.
    @Test
    fun aGiveUpOfOneAccount_doesNotStallTheSameAddOfAnother() {
        val (tv, stamp) = adding()
        val ownership = SelectionOwnership(tv.library)
        val mine = assertNotNull(tv.stored()).copy(owner = ACCOUNT)

        ownership.giveUp(ACCOUNT, libB, stamp)
        ownership.publish(mine)
        assertEquals(setOf(libB), assertNotNull(ownership.selection.value).stalled)

        ownership.publish(mine.copy(owner = other))
        assertTrue(assertNotNull(ownership.selection.value).stalled.isEmpty(), "not given up on the other account")
    }

    // What no stored add matches is dropped when the choice is published, so the set does not grow for ever.
    @Test
    fun aGiveUpNoStoredAddMatches_isPrunedAtPublish() {
        val (tv, stamp) = adding()
        val ownership = SelectionOwnership(tv.library)
        val mine = assertNotNull(tv.stored()).copy(owner = ACCOUNT)

        ownership.giveUp(ACCOUNT, libB, stamp)
        ownership.publish(mine.copy(adding = emptyMap()))
        assertEquals(0, ownership.givenUpCount(), "the add is no longer stored")

        ownership.publish(mine)
        assertTrue(assertNotNull(ownership.selection.value).stalled.isEmpty(), "and it stays dropped")
    }
}
