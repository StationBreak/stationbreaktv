// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.selection.ChoiceView
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.feature.guide.ui.GuideMessage
import tv.stationbreak.feature.guide.ui.guideMessageOf

/** Every library this TV chose is gone (spec 26): the guide says so, and its menu row is where the viewer goes. */
class GuideNoLibrariesTest : GuideRig() {
    private val gone = ChoiceView(setOf(ItemId("lib-a")), emptySet(), gone = true)
    private val kept = gone.copy(gone = false)

    private fun GuideViewModel.menu(): GuideMenuEntry? = snapshot.value.menu

    @Test
    fun goneSelection_guideShowsMessage_menuReachable() =
        guideTest { vm ->
            choices.selection.value = gone
            val events = mutableListOf<GuideEvent>()
            backgroundScope.launch { vm.events.collect { events += it } }
            vm.open(CH7)
            runCurrent()

            // The message stands where the grid goes, whatever the schedule still holds.
            assertEquals(GuideMessage.NoLibraries, guideMessageOf(vm.snapshot.value.state, noLibraries = true))
            // The focus is on the menu row, on Settings, where the libraries are chosen again.
            assertEquals(GuideMenuEntry.Settings, vm.menu())

            // Down never leaves the menu row for a grid that is not shown; Left and Right still move along it.
            vm.move(GuideDirection.Down, false, 0)
            assertEquals(GuideMenuEntry.Settings, vm.menu())
            vm.move(GuideDirection.Left, false, 0)
            assertEquals(GuideMenuEntry.Channels, vm.menu())
            vm.move(GuideDirection.Right, false, 0)
            assertEquals(GuideMenuEntry.Settings, vm.menu())

            // Select opens Settings; Back from the menu row is the activity's, as ever.
            vm.select()
            runCurrent()
            assertEquals(listOf(GuideEvent.OpenSettings), events)
            assertFalse(vm.back())
        }

    @Test
    fun selectionGoneWhileOpen_focusGoesToTheMenu_andBackWhenItReturns() =
        guideTest { vm ->
            choices.selection.value = kept
            vm.open(CH7)
            runCurrent()
            assertNull(vm.menu())
            assertNull(guideMessageOf(vm.snapshot.value.state, noLibraries = false))

            choices.selection.value = gone
            runCurrent()
            assertEquals(GuideMenuEntry.Settings, vm.menu())

            // A library chosen again: Down goes back to the grid.
            choices.selection.value = kept
            runCurrent()
            vm.move(GuideDirection.Down, false, 0)
            assertNull(vm.menu())
            assertTrue(vm.snapshot.value.state is GuideState.Ready)
        }

    @Test
    fun theMessages_perState() {
        assertNull(guideMessageOf(GuideState.Closed, noLibraries = true))
        assertEquals(GuideMessage.NoLibraries, guideMessageOf(GuideState.Loading, noLibraries = true))
        assertEquals(GuideMessage.Loading, guideMessageOf(GuideState.Loading, noLibraries = false))
        assertEquals(GuideMessage.Empty, guideMessageOf(GuideState.Empty, noLibraries = false))
    }
}
