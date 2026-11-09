// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN

/**
 * The channel column as a position of the guide's focus (#631): Left from a row's earliest cell reaches it, Select
 * there asks for the channel's properties, and Right or Back return to the row's cell.
 *
 * The guide opens at minute 65, so every row's first cell is the one on air: Left right after the open is Left from the
 * earliest cell.
 */
class GuideChannelFocusTest : GuideRig() {
    private fun GuideViewModel.press(direction: GuideDirection) = move(direction, repeat = false, atMs = 0)

    private fun GuideViewModel.onChannel() = snapshot.value.onChannel

    private fun TestScope.eventsOf(vm: GuideViewModel): MutableList<GuideEvent> =
        mutableListOf<GuideEvent>().also { got -> backgroundScope.launch { vm.events.collect { got += it } } }

    @Test
    fun leftFromTheEarliestCellMovesToTheChannelAndKeepsTheRow() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            val cell = vm.focused()
            assertFalse(vm.onChannel())
            vm.press(GuideDirection.Left)
            assertTrue(vm.onChannel())
            assertEquals(cell, vm.focused())
            assertNull(vm.snapshot.value.menu)
        }

    @Test
    fun leftFromALaterCellMovesAlongTheRowFirst() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            val first = vm.focused()
            vm.press(GuideDirection.Right)
            vm.press(GuideDirection.Left)
            assertFalse(vm.onChannel())
            assertEquals(first, vm.focused())
            vm.press(GuideDirection.Left)
            assertTrue(vm.onChannel())
        }

    @Test
    fun rightReturnsToTheCellOfTheRow() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            val cell = vm.focused()
            vm.press(GuideDirection.Left)
            vm.press(GuideDirection.Right)
            assertFalse(vm.onChannel())
            assertEquals(cell, vm.focused())
        }

    @Test
    fun leftOnTheChannelDoesNothing() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            vm.press(GuideDirection.Left)
            val before = vm.snapshot.value
            vm.press(GuideDirection.Left)
            assertEquals(before, vm.snapshot.value)
        }

    @Test
    fun upAndDownMoveBetweenChannelsStayingOnTheColumn() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            vm.press(GuideDirection.Left)
            vm.press(GuideDirection.Down)
            assertEquals(CH9, vm.focused().channel)
            assertTrue(vm.onChannel())
            vm.press(GuideDirection.Up)
            vm.press(GuideDirection.Up)
            assertEquals(CH5, vm.focused().channel)
            assertTrue(vm.onChannel())
        }

    @Test
    fun selectOnTheChannelAsksForItsPropertiesAndDoesNotTune() =
        guideTest { vm ->
            val got = eventsOf(vm)
            vm.open(CH7)
            runCurrent()
            vm.press(GuideDirection.Left)
            vm.select()
            runCurrent()
            assertEquals(listOf<GuideEvent>(GuideEvent.OpenChannelProperties(CH7)), got)
            assertTrue(vm.onChannel())
        }

    @Test
    fun backLeavesTheColumnBeforeAnythingElse() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            val cell = vm.focused()
            vm.press(GuideDirection.Left)
            assertTrue(vm.back())
            assertFalse(vm.onChannel())
            assertNull(vm.snapshot.value.menu)
            assertEquals(cell, vm.focused())
            // The next Back is the menu row's, as it was before the column existed.
            assertTrue(vm.back())
            assertEquals(GuideMenuEntry.Channels, vm.snapshot.value.menu)
            assertFalse(vm.onChannel())
        }

    @Test
    fun theColumnKeepsTheFocusAcrossASheetAndTheClockAfterIt() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            vm.press(GuideDirection.Left)
            val cell = vm.focused()
            vm.channelsOpened()
            after(MIN)
            vm.propertiesClosed()
            runCurrent()
            after(MIN)
            assertTrue(vm.onChannel())
            assertEquals(cell.channel, vm.focused().channel)
        }

    @Test
    fun aGuideClosedAndOpenedAgainStartsOnTheGrid() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            vm.press(GuideDirection.Left)
            vm.close()
            assertFalse(vm.onChannel())
            vm.open(CH7)
            runCurrent()
            assertFalse(vm.onChannel())
        }
}
