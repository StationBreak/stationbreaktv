// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.MovedChannel
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7

/**
 * The note of moved channels (#1107): read, never cleared, when the guide opens with Family Mode off; cleared only
 * by the dismissal, and kept pending when that cannot be saved.
 */
class GuideMovedNoteTest : GuideRig() {
    private val told = listOf(MovedChannel("Test One", 47, 100), MovedChannel("Test Two", 12, 34))

    @Test
    fun peekingTwiceThenDismissingLeavesNothingForTheNextOpening() =
        guideTest { vm ->
            movedEditor.moved = told
            vm.open(CH7)
            runCurrent()
            assertEquals(told, vm.movedNote.value)

            // A second look (the guide reopened before any dismissal) shows the same: nothing was cleared.
            vm.close()
            vm.open(CH7)
            runCurrent()
            assertEquals(told, vm.movedNote.value)
            assertEquals(0, movedEditor.acknowledgements)

            vm.movedDismissed()
            runCurrent()
            assertEquals(emptyList<MovedChannel>(), vm.movedNote.value)
            assertEquals(1, movedEditor.acknowledgements)

            vm.close()
            vm.open(CH7)
            runCurrent()
            assertEquals("gone once dismissed", emptyList<MovedChannel>(), vm.movedNote.value)
        }

    @Test
    fun aFailedSaveGoesFromTheScreenAtOnceButStaysPending() =
        guideTest { vm ->
            movedEditor.moved = told
            movedEditor.acknowledgeSaves = false
            vm.open(CH7)
            runCurrent()

            vm.movedDismissed()
            runCurrent()
            assertEquals("the viewer pressed OK: it goes", emptyList<MovedChannel>(), vm.movedNote.value)
            assertEquals("but the notices are still pending", told, movedEditor.moved)

            vm.close()
            vm.open(CH7)
            runCurrent()
            assertEquals("so the next opening shows it again", told, vm.movedNote.value)
        }

    @Test
    fun familyModeDefersItToALaterOpening() =
        guideTest { vm ->
            movedEditor.moved = told
            kidsLock.lock()
            vm.open(CH7)
            runCurrent()
            assertEquals(emptyList<MovedChannel>(), vm.movedNote.value)

            // Turned off inside the same display: the note waits for the next opening, not over the Settings sheet.
            kidsLock.unlock()
            runCurrent()
            assertEquals(emptyList<MovedChannel>(), vm.movedNote.value)
            assertEquals("still pending", told, movedEditor.moved)

            vm.close()
            vm.open(CH7)
            runCurrent()
            assertEquals(told, vm.movedNote.value)
        }

    @Test
    fun nothingIsShownBeforeTheLockHasBeenRead() =
        guideTest { vm ->
            movedEditor.moved = told
            vm.open(CH7)
            // The lock's first read has not run yet.
            assertTrue(vm.movedNote.value.isEmpty())
            runCurrent()
            assertEquals(told, vm.movedNote.value)
        }

    @Test
    fun killedWhileOpenItIsShownAgain() =
        guideTest { vm ->
            movedEditor.moved = told
            vm.open(CH7)
            runCurrent()
            assertEquals(told, vm.movedNote.value)

            // The process dies with the sheet up: no dismissal was made, so a new ViewModel finds it pending.
            val restarted = newGuide(65)
            try {
                restarted.open(CH7)
                runCurrent()
                assertEquals(told, restarted.movedNote.value)
            } finally {
                restarted.close()
            }
        }

    @Test
    fun nothingPendingShowsNothingAndAcknowledgesNothing() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            assertEquals(emptyList<MovedChannel>(), vm.movedNote.value)
            vm.movedDismissed()
            assertEquals(0, movedEditor.acknowledgements)
        }
}
