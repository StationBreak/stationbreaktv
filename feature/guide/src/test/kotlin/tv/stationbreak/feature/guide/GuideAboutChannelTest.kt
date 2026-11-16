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
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7

/**
 * Making the channel box discoverable (#803): the hint row says Left reaches the channel column where it would, and
 * the menu sheet has an About row for the focused channel's properties while there is a grid.
 *
 * The guide opens at minute 65, so every row's first cell is the one on air: Left right after the open is Left from the
 * row's earliest cell.
 */
class GuideAboutChannelTest : GuideRig() {
    private fun GuideViewModel.press(direction: GuideDirection) = move(direction, repeat = false, atMs = 0)

    private fun GuideViewModel.menu() = snapshot.value.menu

    private fun TestScope.eventsOf(vm: GuideViewModel): MutableList<GuideEvent> =
        mutableListOf<GuideEvent>().also { got -> backgroundScope.launch { vm.events.collect { got += it } } }

    @Test
    fun leftGoesToTheChannelOnTheEarliestCellOnly() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            assertTrue(vm.snapshot.value.leftGoesToChannel(0L))
            vm.press(GuideDirection.Right)
            assertFalse(vm.snapshot.value.leftGoesToChannel(0L))
            vm.press(GuideDirection.Left)
            assertTrue(vm.snapshot.value.leftGoesToChannel(0L))
        }

    @Test
    fun theHintAndTheMoveAgree() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            repeat(3) {
                val says = vm.snapshot.value.leftGoesToChannel(0L)
                vm.press(GuideDirection.Left)
                assertEquals(says, vm.snapshot.value.onChannel)
                if (vm.snapshot.value.onChannel) vm.press(GuideDirection.Right)
                vm.press(GuideDirection.Right)
            }
        }

    @Test
    fun leftGoesToNoChannelFromTheChannelTheSheetOrNoGrid() =
        guideTest { vm ->
            assertFalse(GuideSnapshot.Closed.leftGoesToChannel(0L))
            vm.open(CH7)
            runCurrent()
            vm.press(GuideDirection.Left)
            assertTrue(vm.snapshot.value.onChannel)
            assertFalse(vm.snapshot.value.leftGoesToChannel(0L))
            vm.press(GuideDirection.Right)
            assertTrue(vm.toggleMenu())
            assertFalse(vm.snapshot.value.leftGoesToChannel(0L))
            assertFalse(vm.snapshot.value.copy(menu = null, noLibraries = true).leftGoesToChannel(0L))
        }

    @Test
    fun theSheetOpensOnChannelsAndAboutIsOneUpFromIt() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            assertTrue(vm.toggleMenu())
            assertEquals(GuideMenuEntry.Channels, vm.menu())
            val row = vm.snapshot.value.aboutRow()
            assertEquals(CH7, row?.channel)
            assertTrue(row?.name?.isNotBlank() == true)
            vm.press(GuideDirection.Up)
            assertEquals(GuideMenuEntry.About, vm.menu())
            vm.press(GuideDirection.Up)
            assertEquals(GuideMenuEntry.About, vm.menu())
            vm.press(GuideDirection.Down)
            assertEquals(GuideMenuEntry.Channels, vm.menu())
        }

    @Test
    fun theSheetOpenedOnTheChannelColumnHasTheRowToo() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            vm.press(GuideDirection.Left)
            assertTrue(vm.toggleMenu())
            assertEquals(GuideMenuEntry.Channels, vm.menu())
            assertEquals(CH7, vm.snapshot.value.aboutRow()?.channel)
        }

    @Test
    fun selectOnAboutAsksForTheFocusedChannelsPropertiesAndKeepsTheSheet() =
        guideTest { vm ->
            val got = eventsOf(vm)
            vm.open(CH7)
            runCurrent()
            vm.toggleMenu()
            vm.press(GuideDirection.Up)
            vm.select()
            runCurrent()
            assertEquals(listOf<GuideEvent>(GuideEvent.OpenChannelProperties(CH7)), got)
            assertEquals(GuideMenuEntry.About, vm.menu())
            // The properties sheet closing returns to the sheet as it was.
            vm.propertiesClosed()
            assertEquals(GuideMenuEntry.About, vm.menu())
        }

    @Test
    fun theSheetKeepsTheFocusWhereItWasWhenItCloses() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            val cell = vm.focused()
            vm.toggleMenu()
            vm.press(GuideDirection.Up)
            assertTrue(vm.toggleMenu())
            assertNull(vm.menu())
            assertEquals(cell, vm.focused())
        }

    @Test
    fun noGridHasNoAboutRow() {
        assertNull(GuideSnapshot.Closed.aboutRow())
        assertNull(GuideSnapshot(GuideState.Empty, null, menu = GuideMenuEntry.Channels).aboutRow())
        assertNull(GuideSnapshot(GuideState.Loading, null, menu = GuideMenuEntry.Channels).aboutRow())
    }

    @Test
    fun noLibrariesHasNoAboutRowWhateverTheGridHeld() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            assertEquals(CH7, vm.snapshot.value.aboutRow()?.channel)
            assertNull(vm.snapshot.value.copy(noLibraries = true).aboutRow())
        }
}
