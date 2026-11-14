// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelStatus
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.PlayKey
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.selection.ChoiceView
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.feature.guide.ui.GuideMessage
import tv.stationbreak.feature.guide.ui.GuidePanelKind
import tv.stationbreak.feature.guide.ui.guideMessageOf
import tv.stationbreak.feature.guide.ui.guidePanelOf
import kotlin.time.Instant

/** Every library this TV chose is gone (spec 26): the guide says so, and its menu row is where the viewer goes. */
class GuideNoLibrariesTest : GuideRig() {
    private val gone = ChoiceView(setOf(ItemId("lib-a")), emptySet(), gone = true)
    private val kept = gone.copy(gone = false)

    private fun GuideViewModel.menu(): GuideMenuEntry? = snapshot.value.menu

    private fun summary(
        id: ChannelId,
        number: Int,
    ) = ChannelSummary(
        id,
        number,
        "Test Channel $number",
        ChannelStatus.On,
        ChannelKind.Starter,
        Instant.fromEpochMilliseconds(T0),
        number,
        kid = false,
        optIn = false,
        playKey = PlayKey("test"),
    )

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

            // The sheet is all there is for a grid that is not shown: Up and Down move along it, Left and Right do not.
            vm.move(GuideDirection.Right, false, 0)
            assertEquals(GuideMenuEntry.Settings, vm.menu())
            vm.move(GuideDirection.Up, false, 0)
            assertEquals(GuideMenuEntry.Channels, vm.menu())
            vm.move(GuideDirection.Down, false, 0)
            assertEquals(GuideMenuEntry.Settings, vm.menu())
            // The Menu key does not close it either: there is nothing behind it to return to.
            assertFalse(vm.toggleMenu())
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

            // A library chosen again: the sheet stays until it is closed, and Back then closes it to the grid.
            choices.selection.value = kept
            runCurrent()
            assertEquals(GuideMenuEntry.Settings, vm.menu())
            assertTrue(vm.back())
            assertNull(vm.menu())
            assertTrue(vm.snapshot.value.state is GuideState.Ready)
        }

    // A lineup change reloads the grid in a fresh session: the selection is still followed after it.
    @Test
    fun selectionGoneAfterALineupReload_focusGoesToTheMenu() =
        guideTest { vm ->
            choices.selection.value = kept
            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7))
            vm.open(CH7)
            runCurrent()
            val calls = schedule.calls

            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
            runCurrent()
            assertTrue("the lineup change did not reload the grid", schedule.calls > calls)
            assertNull(vm.menu())

            choices.selection.value = gone
            runCurrent()
            assertEquals(GuideMenuEntry.Settings, vm.menu())
        }

    // On the channel column (#631) when the libraries go: the focus leaves it for the menu row, and Select there is
    // Settings, never the channel's properties.
    @Test
    fun selectionGoneOnTheChannelColumn_focusGoesToSettings_notTheProperties() =
        guideTest { vm ->
            choices.selection.value = kept
            val events = mutableListOf<GuideEvent>()
            backgroundScope.launch { vm.events.collect { events += it } }
            vm.open(CH7)
            runCurrent()
            vm.move(GuideDirection.Left, false, 0)
            assertTrue(vm.snapshot.value.onChannel)

            choices.selection.value = gone
            runCurrent()
            assertEquals(GuideMenuEntry.Settings, vm.menu())
            assertFalse(vm.snapshot.value.onChannel)

            vm.select()
            runCurrent()
            assertEquals(listOf(GuideEvent.OpenSettings), events)
        }

    // One value: the snapshot carries the flag, set and cleared with the selection; the screen reads nothing else.
    @Test
    fun theSnapshotCarriesTheFlag_withTheSelection() =
        guideTest { vm ->
            choices.selection.value = kept
            vm.open(CH7)
            runCurrent()
            assertFalse(vm.snapshot.value.noLibraries)

            choices.selection.value = gone
            runCurrent()
            assertTrue(vm.snapshot.value.noLibraries)

            // It outlives a rebuild of the grid.
            vm.move(GuideDirection.Left, false, 0)
            vm.move(GuideDirection.Right, false, 0)
            assertTrue(vm.snapshot.value.noLibraries)

            choices.selection.value = kept
            runCurrent()
            assertFalse(vm.snapshot.value.noLibraries)
        }

    @Test
    fun goneFromTheStart_theFirstPublishedGridAlreadyCarriesTheFlag() =
        guideTest { vm ->
            choices.selection.value = gone
            vm.open(CH7)
            runCurrent()
            assertTrue(vm.snapshot.value.state is GuideState.Ready)
            assertTrue(vm.snapshot.value.noLibraries)
        }

    // The details panel shows nothing of a programme while the selection is gone; Settings still has its own panel.
    @Test
    fun thePanel_showsNoProgrammeWhileGone() {
        fun panel(
            settings: Boolean = false,
            gone: Boolean = false,
            onChannel: Boolean = false,
        ) = guidePanelOf(settings = settings, noLibraries = gone, onChannel = onChannel)

        assertEquals(GuidePanelKind.Details, panel())
        assertEquals(GuidePanelKind.Empty, panel(gone = true))
        assertEquals(GuidePanelKind.Empty, panel(gone = true, onChannel = true))
        assertEquals(GuidePanelKind.Settings, panel(gone = true, settings = true))
        assertEquals(GuidePanelKind.Channel, panel(onChannel = true))
    }

    // The Libraries screen hides the guide as channel management does: no reload under it, and the menu stays on
    // Settings, where the panel was left open, when it closes.
    @Test
    fun whileLibrariesShow_aLineupChangeWaits_andTheMenuStaysOnSettings() =
        guideTest { vm ->
            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7))
            vm.open(CH7)
            runCurrent()
            while (vm.menu() == null) check(vm.back()) { "Back did not reach the menu" }
            vm.move(GuideDirection.Down, false, 0)
            assertEquals(GuideMenuEntry.Settings, vm.menu())
            vm.librariesOpened()
            val calls = schedule.calls

            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
            runCurrent()
            assertEquals("the grid was read again under the Libraries screen", calls, schedule.calls)

            vm.librariesClosed()
            runCurrent()
            assertTrue("the grid was not read again when Libraries closed", schedule.calls > calls)
            assertEquals(GuideMenuEntry.Settings, vm.menu())
        }

    @Test
    fun theMessages_perState() {
        assertNull(guideMessageOf(GuideState.Closed, noLibraries = true))
        assertEquals(GuideMessage.NoLibraries, guideMessageOf(GuideState.Loading, noLibraries = true))
        assertEquals(GuideMessage.Loading, guideMessageOf(GuideState.Loading, noLibraries = false))
        assertEquals(GuideMessage.Empty, guideMessageOf(GuideState.Empty, noLibraries = false))
    }
}
