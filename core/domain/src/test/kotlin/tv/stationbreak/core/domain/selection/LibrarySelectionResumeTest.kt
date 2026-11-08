// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.lineupOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The resume at start, and what it repairs of each crash (spec 26, Part 4 §2 "resumeAtStart", crash rows). */
internal class LibrarySelectionResumeTest : SelectionTestBase() {
    @Test
    fun everyOperation_waitsForTheResume_andTheResumeCompletesEvenWhenItFails() =
        runBlocking<Unit> {
            val tv = Tv()
            val waiting = async { tv.selection.change(setOf(libA)) }
            repeat(5) { yield() }
            assertFalse(waiting.isCompleted, "no operation before the resume")
            tv.library.failApplies = 1

            tv.selection.resumeAtStart()

            assertTrue(SelectionEvent.SelectionResumeFailed in tv.events || SelectionEvent.RoomBehind in tv.events)
            withTimeout(5_000) { tv.selection.await() }
            assertEquals(ChangeResult.Saved, withTimeout(5_000) { waiting.await() })
        }

    @Test
    fun theResume_publishesTheStoredSelection_andItsSeq() {
        val tv = Tv(stored = StoredSelection(setOf(libA, libB), seq = 6L, adding = mapOf(libC to 6L)))

        tv.resume()

        assertEquals(6L, tv.selection.selectionChanges.value)
        assertEquals(ChoiceView(setOf(libA, libB), setOf(libC), gone = false), tv.selection.selection.value)
    }

    @Test
    fun resume_unprovenChosenAfterFirstSync_rewalks_noThrow_onAirUnchanged() {
        val tv = Tv(chosen = setOf(libA), stored = StoredSelection(setOf(libA, libB), seq = 1L))
        tv.stampInRoom(libB, 1L, setOf(libA))
        tv.seedItems(comediesB)
        val onAir = tv.rig.onAir(ownB.id, atMs)

        tv.resume()

        assertEquals(listOf(libB to 1L), tv.library.reverted)
        val row = assertNotNull(tv.row(libB))
        assertTrue(row.rewalkPending && !row.fetched && row.addStamp == null)
        assertEquals(1, tv.stayCurrentRequests)
        assertEquals(setOf(libA, libB), assertNotNull(tv.stored()).chosen, "the choice is as saved")
        assertEquals(onAir, tv.rig.onAir(ownB.id, atMs))
    }

    @Test
    fun aCrashBetweenALandingsSaveAndItsCacheLanding_isLandedAtResume() {
        val tv = Tv(stored = StoredSelection(setOf(libA, libB, libC), seq = 3L))
        tv.stampInRoom(libC, 3L, setOf(libA, libB))
        tv.fake.proveWalk(ACCOUNT, libC, 3L)

        tv.resume()

        assertEquals(listOf(libC to 3L), tv.library.landed)
        assertNull(tv.row(libC)?.addStamp)
    }

    @Test
    fun anAddAlreadyReadWithNoStamp_isDroppedFromTheAdds_withANewSeq_andTheCacheLetsItGo() {
        // Only a downgrade or a bug leaves this: an id being added whose row is fetched, with no stamp and no hold.
        val tv =
            Tv(
                lineup = lineupOf(laughs),
                stored = StoredSelection(setOf(libA), seq = 2L, adding = mapOf(libB to 2L)),
                chosen = setOf(libA, libB),
            )

        tv.resume()

        val stored = assertNotNull(tv.stored())
        assertEquals(emptyMap(), stored.adding)
        assertTrue(stored.seq > 2L)
        assertNull(tv.row(libB), "outside the kept set, nothing on air: deleted, so a later tick meets a new row")
        assertTrue(SelectionEvent.AddDropped in tv.events)
    }

    @Test
    fun theResume_keepsEveryAdd_stampedAsSaved_andRestartsThePasses() {
        val tv = Tv(stored = StoredSelection(setOf(libA, libB), seq = 4L, adding = mapOf(libC to 4L)))

        tv.resume()

        assertEquals(4L, tv.row(libC)?.addStamp)
        assertEquals(1, tv.restarts)
    }

    @Test
    fun aCrashBetweenAnUntickSaveAndItsCacheWrite_isHeldAtResume_byItsPin() {
        val tv = Tv().ready(setOf(libA, libB))
        tv.library.failApplies = 1
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA)))
        assertNull(tv.row(libB)?.heldUntilMs, "the cache write failed")
        // The process dies here; a new one resumes over the same stores.
        val again = tv.restartedSelection()

        runBlocking { again.resumeAtStart() }

        assertNotNull(tv.row(libB)?.heldUntilMs, "held for its pinned programme")
    }

    @Test
    fun theSeqIsRaisedAboveTheCachesStamps_atStart() {
        val tv = Tv(stored = StoredSelection(setOf(libA, libB), seq = 2L, adding = mapOf(libC to 2L)))
        tv.stampInRoom(libC, 7L, setOf(libA, libB))

        tv.resume()

        assertTrue(assertNotNull(tv.stored()).seq >= 8L)
    }

    @Test
    fun theRaiseOverANewerAppsLineup_savesTheResetSeqToo() {
        val tv = Tv(stored = StoredSelection(setOf(libA, libB), seq = 2L, adding = mapOf(libC to 2L)))
        tv.stampInRoom(libC, 7L, setOf(libA, libB))
        runBlocking {
            tv.rig.store.inner
                .save(ACCOUNT, """{"version":99}""".encodeToByteArray())
        }

        tv.resume()

        val stored = assertNotNull(tv.stored())
        // One above max(stored, top) = 7 is skipped: a newer build's hidden repair there stays below the reset seq.
        assertEquals(9L, stored.seq)
        assertEquals(stored.seq, stored.resetSeq)
    }

    @Test
    fun aChosenLibraryLeftPendingAndUnmarked_startsTheFirstSync() {
        // The setup crash row: saved and branched, then the process died before the first sync was asked for.
        val tv = Tv(chosen = setOf(libA), stored = StoredSelection(setOf(libA, libC), seq = 2L, resetSeq = 2L))
        runBlocking { tv.fake.applySelection(ACCOUNT, setOf(libA, libC), nowMs = atMs) }
        assertFalse(assertNotNull(tv.row(libC)).fetched)

        tv.resume()

        assertEquals(1, tv.firstSync.ensureCalls)
    }

    @Test
    fun firstSyncLibraries_branchesTheChosen_keepsTheAdds_andGivesTheChosen() {
        val tv = Tv().ready(setOf(libA, libB, libC, libD))
        tv.change(setOf(libA, libB, libC, libD))
        val stored = assertNotNull(tv.stored())
        val stamp = stored.adding.getValue(libC)
        tv.fake.proveWalk(ACCOUNT, libC, stamp)
        // A landing's save committed and the process died before its cache landing: C is chosen, still stamped.
        val landed = stored.copy(chosen = stored.chosen + libC, seq = stored.seq + 1, adding = stored.adding - libC)
        assertTrue(runBlocking { tv.settings.inner.saveSelection(landed) })

        val chosen = runBlocking { tv.selection.firstSyncLibraries(ACCOUNT) }

        assertEquals(setOf(libA, libB, libC), chosen)
        assertEquals(libC to stamp, tv.library.landed.last())
        assertEquals(landed.adding.getValue(libD), tv.row(libD)?.addStamp, "the add stays, with its stamp")
        assertEquals(
            setOf(libA, libB, libC, libD),
            tv.library.applied
                .last()
                .keep,
        )
    }
}
