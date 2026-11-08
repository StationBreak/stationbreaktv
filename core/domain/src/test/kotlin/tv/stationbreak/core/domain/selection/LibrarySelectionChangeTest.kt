// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.lineupOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A change of the chosen libraries after setup (spec 26, Part 4 §2 "change"). */
internal class LibrarySelectionChangeTest : SelectionTestBase() {
    @Test
    fun tick_storesTheAddWithThisChangesSeq_stampsTheRow_restartsThePasses() {
        val tv = Tv().ready(setOf(libA, libB, libC))

        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB, libC)))

        val stored = assertNotNull(tv.stored())
        assertEquals(setOf(libA, libB), stored.chosen, "an add is not chosen until it lands")
        assertEquals(mapOf(libC to stored.seq), stored.adding)
        assertTrue(stored.seq > 1L)
        assertEquals(stored.seq, tv.row(libC)?.addStamp)
        assertEquals(1, tv.restarts)
        assertEquals(ChoiceView(setOf(libA, libB), setOf(libC), gone = false), tv.selection.selection.value)
    }

    // Binding item 1: the seq the refresher follows moves only once the save has committed.
    @Test
    fun theSeq_isPublishedOnlyAfterTheSaveCommits_andEverySaveThatChangesWhatPlaysMovesIt() {
        val tv = Tv().ready(setOf(libA, libB, libC))
        val before = tv.selection.selectionChanges.value
        assertEquals(1L, before, "the resume publishes the stored seq")
        val seen = mutableListOf<Long>()
        tv.settings.beforeSave = { seen += tv.selection.selectionChanges.value }

        tv.change(setOf(libA))

        assertEquals(listOf(before), seen, "not published while the save runs")
        val after = assertNotNull(tv.stored()).seq
        assertTrue(after > before)
        assertEquals(after, tv.selection.selectionChanges.value)

        // A save that does not commit moves nothing.
        tv.settings.failSaves = 2
        assertEquals(ChangeResult.Failed, tv.change(setOf(libA, libC)))
        assertEquals(after, tv.selection.selectionChanges.value)
        assertEquals(after, assertNotNull(tv.stored()).seq)
    }

    @Test
    fun untick_aLibraryOnAir_holdsIt_untilThePinsEndPlusThePauseHold() {
        val tv = Tv().ready(setOf(libA, libB))

        assertEquals(ChangeResult.Saved, tv.change(setOf(libA)))

        assertEquals(setOf(libA), assertNotNull(tv.stored()).chosen)
        val pins = runBlocking { tv.rig.repairer.heldPins(ACCOUNT, atMs) }
        val until = assertNotNull(pins[libB], "the own channel's programme of B was on air")
        assertTrue(until > atMs)
        assertEquals(until, tv.row(libB)?.heldUntilMs)
        assertEquals(
            mapOf(libB to until),
            tv.library.applied
                .last()
                .holds,
        )
        assertEquals(0, tv.restarts, "no add changed")
    }

    @Test
    fun untick_aLibraryWithNothingOnAir_deletesIt() {
        // Nothing of B on air: no channel airs a crime, and B holds only comedies.
        val tv = Tv(lineup = lineupOf(whodunit)).ready(setOf(libA, libB))

        assertEquals(ChangeResult.Saved, tv.change(setOf(libA)))

        assertNull(tv.row(libB), "no pin: no hold, its rows go")
    }

    // Binding item 2: the holds given are max(existing, heldPins), never lower than the row's own.
    @Test
    fun aHoldGiven_neverLowersTheHoldARowHas() {
        val tv = Tv().ready(setOf(libA, libB, libC))
        tv.change(setOf(libA))
        val pinned = assertNotNull(tv.row(libB)?.heldUntilMs)
        val later = pinned + 5 * hour
        runBlocking { tv.fake.applySelection(ACCOUNT, setOf(libA), holds = mapOf(libB to later), nowMs = atMs) }
        assertEquals(later, tv.row(libB)?.heldUntilMs)

        tv.change(setOf(libA, libC))

        val given =
            tv.library.applied
                .last()
                .holds
        assertEquals(later, given[libB], "the row's later hold, not the pin's")
        assertEquals(later, tv.row(libB)?.heldUntilMs)
    }

    @Test
    fun theLastChosenLibrary_cannotBeUnticked_evenWhileAnotherIsBeingAdded() {
        val tv = Tv(chosen = setOf(libA)).ready(setOf(libA, libC))
        tv.change(setOf(libA, libC))
        val stored = tv.stored()
        val applied = tv.library.applied.size

        assertEquals(ChangeResult.LastLibrary, tv.change(setOf(libC)))
        assertEquals(ChangeResult.LastLibrary, tv.change(emptySet()))

        assertEquals(stored, tv.stored())
        assertEquals(applied, tv.library.applied.size)
    }

    @Test
    fun aLibraryToAdd_thatTheLastListingLacked_isNotListed() {
        val tv = Tv()
        tv.resume()
        assertEquals(ChangeResult.NotListed, tv.change(setOf(libA, libB, libC)), "no listing yet")
        tv.listing(setOf(libA, libB))
        assertEquals(ChangeResult.NotListed, tv.change(setOf(libA, libB, libC)))
        // An untick needs no listing.
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA)))
        assertEquals(setOf(libA), assertNotNull(tv.stored()).chosen)
    }

    @Test
    fun noChange_writesNothing() {
        val tv = Tv().ready(setOf(libA, libB))
        val stored = tv.stored()
        val applied = tv.library.applied.size

        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB)))

        assertEquals(stored, tv.stored())
        assertEquals(applied, tv.library.applied.size)
    }

    @Test
    fun aRepairThatCannotBeSaved_failsTheChange_andNothingIsWritten() {
        val tv = Tv().ready(setOf(libA, libB))
        val stored = tv.stored()
        val applied = tv.library.applied.size
        tv.rig.store.inner.failWrites = true

        assertEquals(ChangeResult.Failed, tv.change(setOf(libA)))

        assertEquals(stored, tv.stored())
        assertEquals(applied, tv.library.applied.size)
        assertNull(tv.row(libB)?.heldUntilMs)
        assertEquals(1L, tv.selection.selectionChanges.value)
    }

    // Binding item 4: before any lineup exists, a change is a plain settings save.
    @Test
    fun beforeAnyLineup_aChangeIsAPlainSave() {
        val tv = Tv()
        runBlocking { tv.rig.service.forget(ACCOUNT) }
        tv.ready(setOf(libA, libB, libC))

        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libC)))

        val stored = assertNotNull(tv.stored())
        assertEquals(setOf(libA), stored.chosen)
        assertEquals(mapOf(libC to stored.seq), stored.adding)
        assertTrue(stored.seq > 1L)
        assertNull(tv.row(libB), "nothing on air, so nothing held")
        assertEquals(stored.seq, tv.row(libC)?.addStamp)
        assertEquals(stored.seq, tv.selection.selectionChanges.value)
    }

    @Test
    fun aLineupOnDiskThatCannotBeReadNow_failsTheChange() {
        // The file is there but cannot be read: that is not "no lineup yet", so nothing is saved around it.
        val tv = Tv()
        tv.rig.store.inner.failReads = true
        tv.ready(setOf(libA, libB))
        val stored = tv.stored()

        assertEquals(ChangeResult.Failed, tv.change(setOf(libA)))

        assertEquals(stored, tv.stored())
    }

    @Test
    fun aCancelledCaller_doesNotStopAChangeOnceItWrites() =
        runBlocking<Unit> {
            val tv = Tv().ready(setOf(libA, libB, libC))
            val saving = CompletableDeferred<Unit>()
            val go = CompletableDeferred<Unit>()
            tv.settings.beforeSave = {
                saving.complete(Unit)
                go.await()
            }
            val call = async { tv.selection.change(setOf(libA, libB, libC)) }
            saving.await()
            call.cancel()
            go.complete(Unit)
            runCatching { call.await() }
            withTimeout(5_000) { while (tv.row(libC)?.addStamp == null) delay(10) }

            val stored = assertNotNull(tv.stored())
            assertEquals(stored.seq, tv.row(libC)?.addStamp, "the cache followed the saved change")
        }

    @Test
    fun tickUntickTick_theFirstStampNeverLands() {
        val tv = Tv().ready(setOf(libA, libB, libC))
        tv.change(setOf(libA, libB, libC))
        val first = assertNotNull(tv.stored()).adding.getValue(libC)
        tv.change(setOf(libA, libB))
        tv.change(setOf(libA, libB, libC))
        val second = assertNotNull(tv.stored()).adding.getValue(libC)
        assertTrue(second > first)
        tv.fake.proveWalk(ACCOUNT, libC, second)

        assertEquals(LandResult.NotCurrent, runBlocking { tv.selection.passLanded(ACCOUNT, libC, first) })
        assertEquals(mapOf(libC to second), assertNotNull(tv.stored()).adding)
    }

    // D13: the save stands; the cache is put in line by the next operation.
    @Test
    fun aCacheWriteThatFails_afterTheSave_isSaved_andTheNextOperationPutsTheCacheInLineFirst() {
        val tv = Tv().ready(setOf(libA, libB, libC))
        tv.library.failApplies = 1

        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB, libC)))
        val stored = assertNotNull(tv.stored())
        assertEquals(mapOf(libC to stored.seq), stored.adding)
        assertNull(tv.row(libC), "the cache write failed")
        assertTrue(SelectionEvent.RoomBehind in tv.events)

        runBlocking { tv.selection.reconcile() }
        assertEquals(stored.seq, tv.row(libC)?.addStamp)
    }

    @Test
    fun theSeq_isFlooredAboveEveryStampTheCacheHolds() {
        val stored = StoredSelection(setOf(libA, libB), seq = 2L, adding = mapOf(libC to 2L))
        val tv = Tv(stored = stored).ready(setOf(libA, libB, libC, libD))
        // A stamp above the stored seq (as a downgrade that dropped the seq leaves): no change may take it again.
        tv.stampInRoom(libC, 9L, setOf(libA, libB))

        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB, libC, libD)))

        val after = assertNotNull(tv.stored())
        assertTrue(after.seq >= 10L)
        assertEquals(after.seq, after.adding[libD])
    }

    @Test
    fun aCallFromInsideTheSelection_orFromInsideARepairTurn_failsAtOnce() {
        val tv = Tv().ready(setOf(libA, libB, libC))
        var inner: Throwable? = null
        tv.settings.beforeSave = { inner = runCatching { tv.selection.change(setOf(libA)) }.exceptionOrNull() }

        tv.change(setOf(libA, libB, libC))

        assertTrue(inner is IllegalStateException, "the save runs in the repairer's turn and the selection's lock")
        assertFailsWith<IllegalStateException> {
            runBlocking {
                tv.rig.repairer.saveWithoutRepair(ACCOUNT, 0L) {
                    tv.selection.change(setOf(libA)) == ChangeResult.Saved
                }
            }
        }
    }
}
