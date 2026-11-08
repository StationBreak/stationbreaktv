// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.lineup.ACCOUNT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stamps the selection gives the cache (spec 26, Part 4 §2, §4): a reverted row is held before it is stamped again
 * (the cache refuses a new stamp on a chosen row with neither), and the chosen libraries a reconcile is handed never
 * hold a stamp.
 */
internal class LibrarySelectionStampsTest : SelectionTestBase() {
    // C = {A, B}; B's add was never proven to end, so the resume reverts it: chosen, pending, marked for a rewalk.
    private fun reverted(): Tv {
        // B was being added (stamped, its rows written) when a landing's save made it chosen; its proof never came.
        val tv = Tv(chosen = setOf(libA), stored = StoredSelection(setOf(libA, libB), seq = 1L))
        tv.stampInRoom(libB, 1L, setOf(libA))
        tv.seedItems(comediesB)
        tv.ready(setOf(libA, libB))
        val row = assertNotNull(tv.row(libB))
        assertTrue(row.rewalkPending && !row.fetched && row.addStamp == null && row.heldUntilMs == null)
        return tv
    }

    // Binding item 3, as a viewer reaches it: untick (held by its pin) and tick again before the rewalk ended.
    @Test
    fun aRevertedLibrary_unticked_thenTickedAgainBeforeItsRewalk_isHeld_thenStamped_neverThrows() {
        val tv = reverted()

        assertEquals(ChangeResult.Saved, tv.change(setOf(libA)))
        assertNotNull(tv.row(libB)?.heldUntilMs, "on air, so held")
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB)))

        val stored = assertNotNull(tv.stored())
        assertEquals(mapOf(libB to stored.seq), stored.adding)
        val row = assertNotNull(tv.row(libB))
        assertEquals(stored.seq, row.addStamp)
        assertNull(row.heldUntilMs)
        assertFalse(row.rewalkPending, "the add reads it from the start")
    }

    // Binding item 3, the guarded path: the cache still has the row as reverted (its untick's cache write and the
    // catch-up both failed), so the tick must hold it first; the cache's own rule is not relaxed.
    @Test
    fun aRevertedRowStillInTheCache_isHeldFirst_thenStamped_inTheSameTick() {
        val tv = reverted()
        tv.library.failApplies = 2
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA)), "saved; the cache write failed")
        val before = tv.library.applied.size

        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB)))

        val stored = assertNotNull(tv.stored())
        val writes = tv.library.applied.drop(before)
        assertEquals(2, writes.size, "a hold, then the stamp")
        assertFalse(libB in writes[0].keep)
        assertTrue(assertNotNull(writes[0].holds[libB]) > atMs)
        assertEquals(stored.seq, writes[1].addStamps[libB])
        assertEquals(stored.seq, tv.row(libB)?.addStamp)
        assertNull(tv.row(libB)?.heldUntilMs)
    }

    private fun Tv.assertNoStampedChosen(at: String) {
        val chosen = assertNotNull(stored()).chosen
        val stamped =
            rows()
                .values
                .filter { it.addStamp != null }
                .map { it.id }
                .toSet()
        assertTrue((chosen intersect stamped).isEmpty(), "$at: a chosen library holds a stamp")
    }

    // Binding item 6: a reconcile refuses a stamped row, so every pass over such a chosen set would end Cancelled.
    @Test
    fun theChosenLibraries_neverHoldAStamp_afterAnyOperation() {
        // Resume: a proven and an unproven stamped chosen row (a crash between a landing's save and its landAdd).
        val tv = Tv(chosen = setOf(libA), stored = StoredSelection(setOf(libA, libB, libC), seq = 4L))
        tv.stampInRoom(libB, 3L, setOf(libA))
        tv.fake.proveWalk(ACCOUNT, libB, 3L)
        tv.stampInRoom(libC, 4L, setOf(libA, libB))
        tv.ready(setOf(libA, libB, libC, libD))
        tv.assertNoStampedChosen("resume")

        // A change: the add is stamped, never chosen.
        tv.change(setOf(libA, libB, libC, libD))
        tv.assertNoStampedChosen("change")

        // A landing: chosen once the stamp is cleared.
        val stamp = assertNotNull(tv.stored()).adding.getValue(libD)
        tv.fake.proveWalk(ACCOUNT, libD, stamp)
        assertEquals(LandResult.Landed, runBlocking { tv.selection.passLanded(ACCOUNT, libD, stamp) })
        tv.assertNoStampedChosen("landing")

        // A setup over rows that still hold stamps.
        tv.listing(setOf(libA, libB, libC, libD, libE))
        tv.change(setOf(libA, libB, libC, libD, libE))
        val adding = assertNotNull(tv.stored()).adding
        runBlocking { tv.selection.clear() }
        assertEquals(SetupResult.Saved, runBlocking { tv.selection.choose(setOf(libA) + adding.keys) })
        tv.assertNoStampedChosen("setup")
    }

    @Test
    fun aLandingWhoseCacheLandingFailsOnce_isLandedAgain_neverLeftStampedInTheChosen() {
        val tv = Tv().ready(setOf(libA, libB, libC))
        tv.change(setOf(libA, libB, libC))
        val stamp = assertNotNull(tv.stored()).adding.getValue(libC)
        tv.fake.proveWalk(ACCOUNT, libC, stamp)
        tv.library.refuseLands = 1

        assertEquals(LandResult.Landed, runBlocking { tv.selection.passLanded(ACCOUNT, libC, stamp) })

        tv.assertNoStampedChosen("a landing the cache refused once")
        assertTrue(assertNotNull(tv.row(libC)).fetched)
    }

    @Test
    fun anAddWhoseReadWasNeverProven_isNotLanded() {
        val tv = Tv().ready(setOf(libA, libB, libC))
        tv.change(setOf(libA, libB, libC))
        val stored = assertNotNull(tv.stored())

        val landed = runBlocking { tv.selection.passLanded(ACCOUNT, libC, stored.adding.getValue(libC)) }
        assertEquals(LandResult.Unproven, landed, "not a passing fault: the add pass reads it again")

        assertEquals(stored, tv.stored(), "not chosen: its read has not ended")
    }
}
