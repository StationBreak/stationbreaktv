// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelRow
import tv.stationbreak.core.domain.lineup.ChannelStatus
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.PlayKey
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleWindow
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Instant

/**
 * The menu sheet (spec 21, F2.4, #756): Back on the playing cell and the Menu key open it, Back and the Menu key close
 * it, Channels opens management and Exit leaves.
 */
@Suppress("LargeClass") // One function per behaviour of the sheet and the reload, over one shared rig.
class GuideMenuTest : GuideRig() {
    private fun GuideViewModel.up(
        repeat: Boolean = false,
        atMs: Long = 0,
    ): Unit = move(GuideDirection.Up, repeat, atMs)

    private fun GuideViewModel.down(
        repeat: Boolean = false,
        atMs: Long = 0,
    ): Unit = move(GuideDirection.Down, repeat, atMs)

    private fun GuideViewModel.menu(): GuideMenuEntry? = snapshot.value.menu

    // Back until the focus is on the menu row.
    private fun GuideViewModel.toMenu() {
        while (menu() == null) check(back()) { "Back did not reach the menu" }
    }

    private fun TestScope.eventsOf(vm: GuideViewModel): MutableList<GuideEvent> =
        mutableListOf<GuideEvent>().also { got -> backgroundScope.launch { vm.events.collect { got += it } } }

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
    fun upFromTheTopRowWrapsToTheBottomRow() =
        guideTest { vm ->
            vm.open(CH5)
            runCurrent()
            vm.up()
            assertNull(vm.menu())
            assertEquals(CH9, vm.focused().channel)
        }

    @Test
    fun aHeldUpWaitsAtTheTopRowBeforeItWraps() =
        guideTest { vm ->
            vm.open(CH5)
            runCurrent()
            vm.up(repeat = true, atMs = 1_000)
            assertEquals(CH5, vm.focused().channel)
            vm.up(repeat = true, atMs = 1_399)
            assertEquals(CH5, vm.focused().channel)
            vm.up(repeat = true, atMs = 1_000 + WRAP_HOLD.inWholeMilliseconds)
            assertEquals(CH9, vm.focused().channel)
            // On from the last row the hold scrolls as it did, unpaced.
            vm.up(repeat = true, atMs = 1_430)
            assertEquals(CH7, vm.focused().channel)
        }

    @Test
    fun upNeverOpensTheSheet() =
        guideTest { vm ->
            vm.open(CH5)
            runCurrent()
            repeat(6) { vm.up() }
            assertNull(vm.menu())
        }

    @Test
    fun downStillWrapsFromTheBottom() =
        guideTest { vm ->
            vm.open(CH9)
            runCurrent()
            vm.down()
            assertEquals(CH5, vm.focused().channel)
            assertNull(vm.menu())
        }

    @Test
    fun backWalksOutwardsFromACellToThePlayingCellThenOpensTheSheet() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            val playing = vm.focused()
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            vm.up()
            assertTrue(vm.back())
            assertNull(vm.menu())
            assertEquals(playing, vm.focused())
            assertTrue(vm.back())
            assertEquals(GuideMenuEntry.Channels, vm.menu())
            // The cell last focused is kept.
            assertEquals(playing, vm.focused())
            // The sheet Back opened is left by Back again: not the guide's, the activity leaves live TV.
            assertFalse(vm.back())
            assertEquals(GuideMenuEntry.Channels, vm.menu())
        }

    @Test
    fun backFromThePlayingCellOpensTheSheetAndBackAgainLeavesWhateverTheRow() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            assertTrue(vm.back())
            assertTrue(vm.snapshot.value.menuByBack)
            assertFalse(vm.back())
            vm.down()
            assertEquals(GuideMenuEntry.Settings, vm.menu())
            assertFalse(vm.back())
            vm.down()
            assertEquals(GuideMenuEntry.Exit, vm.menu())
            // Moving in the sheet keeps how it was opened.
            assertTrue(vm.snapshot.value.menuByBack)
            assertFalse(vm.back())
            assertEquals(GuideMenuEntry.Exit, vm.menu())
        }

    @Test
    fun backFromAnotherCellGoesToThePlayingCellThenOpensTheSheetThenLeaves() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            vm.up()
            assertTrue(vm.back())
            assertNull(vm.menu())
            assertTrue(vm.back())
            assertEquals(GuideMenuEntry.Channels, vm.menu())
            assertFalse(vm.back())
        }

    @Test
    fun backInASheetTheMenuKeyOpenedClosesItWithTheFocusWhereItWas() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            val playing = vm.focused()
            assertTrue(vm.toggleMenu())
            assertFalse(vm.snapshot.value.menuByBack)
            vm.down()
            assertEquals(GuideMenuEntry.Settings, vm.menu())
            assertTrue(vm.back())
            assertNull(vm.menu())
            assertFalse(vm.snapshot.value.menuByBack)
            assertEquals(playing, vm.focused())
            // Back from the playing cell is the way out again: it opens the sheet, and Back leaves.
            assertTrue(vm.back())
            assertFalse(vm.back())
        }

    @Test
    fun theMenuKeyClosesASheetBackOpenedAndAReopenByMenuIsClosedByBack() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            val playing = vm.focused()
            assertTrue(vm.back())
            assertTrue(vm.snapshot.value.menuByBack)
            assertTrue(vm.toggleMenu())
            assertNull(vm.menu())
            assertFalse(vm.snapshot.value.menuByBack)
            assertEquals(playing, vm.focused())
            // Opened again by the Menu key, the sheet is closed by Back, not left.
            assertTrue(vm.toggleMenu())
            assertFalse(vm.snapshot.value.menuByBack)
            assertTrue(vm.back())
            assertNull(vm.menu())
            assertEquals(playing, vm.focused())
        }

    @Test
    fun theOpenerSurvivesChannelManagementAndARebuildOfTheGrid() =
        guideTest { vm ->
            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
            vm.open(CH7)
            runCurrent()
            assertTrue(vm.back())
            vm.channelsOpened()
            runCurrent()
            vm.channelsClosed()
            runCurrent()
            assertTrue(vm.snapshot.value.menuByBack)
            assertFalse(vm.back())
            vm.close()
            vm.open(CH7)
            runCurrent()
            assertTrue(vm.toggleMenu())
            vm.channelsOpened()
            runCurrent()
            vm.channelsClosed()
            runCurrent()
            assertEquals(GuideMenuEntry.Channels, vm.menu())
            assertFalse(vm.snapshot.value.menuByBack)
            assertTrue(vm.back())
            assertNull(vm.menu())
        }

    @Test
    fun backWithNoPlayingCellGoesToTheMenu() =
        guideTest { vm ->
            vm.open(ChannelId("gone"))
            runCurrent()
            assertTrue(vm.back())
            assertEquals(GuideMenuEntry.Channels, vm.menu())
            vm.close()
            vm.open(null)
            runCurrent()
            assertTrue(vm.back())
            assertEquals(GuideMenuEntry.Channels, vm.menu())
        }

    @Test
    fun backOnAnEmptyGuideOpensTheSheetAndThenLeaves() =
        guideTest { vm ->
            fake.channels.clear()
            vm.open(CH5)
            runCurrent()
            assertEquals(GuideState.Empty, vm.snapshot.value.state)
            assertTrue(vm.back())
            assertEquals(GuideMenuEntry.Channels, vm.menu())
            assertFalse(vm.back())
        }

    @Test
    fun backWhileTheGuideLoadsIsNotTheGuides() =
        guideTest { vm ->
            schedule.cachedGate = CompletableDeferred()
            vm.open(CH5)
            after(1_000)
            assertEquals(GuideState.Loading, vm.snapshot.value.state)
            assertFalse(vm.back())
            assertNull(vm.menu())
        }

    @Test
    fun upAndDownMoveBetweenTheEntriesAndStopAtTheEnds() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            assertTrue(vm.back())
            vm.up()
            assertEquals(GuideMenuEntry.Channels, vm.menu())
            vm.down()
            assertEquals(GuideMenuEntry.Settings, vm.menu())
            vm.down()
            assertEquals(GuideMenuEntry.Exit, vm.menu())
            vm.down()
            assertEquals(GuideMenuEntry.Exit, vm.menu())
            vm.up()
            assertEquals(GuideMenuEntry.Settings, vm.menu())
            vm.up()
            assertEquals(GuideMenuEntry.Channels, vm.menu())
        }

    @Test
    fun leftAndRightDoNothingInTheSheet() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            assertTrue(vm.back())
            vm.down()
            val before = vm.snapshot.value
            vm.move(GuideDirection.Left, repeat = false, atMs = 0)
            assertEquals(before, vm.snapshot.value)
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            assertEquals(before, vm.snapshot.value)
        }

    @Test
    fun theMenuKeyOpensTheSheetFromACellAndClosesItWithTheFocusWhereItWas() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            vm.down()
            val cell = vm.focused()
            assertTrue(vm.toggleMenu())
            assertEquals(GuideMenuEntry.Channels, vm.menu())
            assertEquals(cell, vm.focused())
            vm.down()
            assertEquals(GuideMenuEntry.Settings, vm.menu())
            assertTrue(vm.toggleMenu())
            assertNull(vm.menu())
            assertEquals(cell, vm.focused())
            assertFalse(vm.snapshot.value.onChannel)
            // Opened again it is on Channels again, not where it was left.
            assertTrue(vm.toggleMenu())
            assertEquals(GuideMenuEntry.Channels, vm.menu())
        }

    @Test
    fun theMenuKeyOpensTheSheetFromTheChannelColumnAndTheFocusReturnsThere() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            vm.move(GuideDirection.Left, repeat = false, atMs = 0)
            assertTrue(vm.snapshot.value.onChannel)
            val cell = vm.focused()
            assertTrue(vm.toggleMenu())
            assertEquals(GuideMenuEntry.Channels, vm.menu())
            assertTrue(vm.snapshot.value.onChannel)
            assertTrue(vm.toggleMenu())
            assertNull(vm.menu())
            assertTrue(vm.snapshot.value.onChannel)
            assertEquals(cell, vm.focused())
            // And Back from the sheet returns there too.
            assertTrue(vm.toggleMenu())
            assertTrue(vm.back())
            assertTrue(vm.snapshot.value.onChannel)
            assertEquals(cell, vm.focused())
        }

    @Test
    fun theMenuKeyOnAClosedGuideDoesNothing() =
        guideTest { vm ->
            assertFalse(vm.toggleMenu())
            assertNull(vm.menu())
        }

    @Test
    fun theSheetKeepsItsRowAcrossARebuildOfTheGrid() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            val onChannel = vm.snapshot.value.onChannel
            assertTrue(vm.toggleMenu())
            vm.down()
            after(3 * MIN)
            assertEquals(GuideMenuEntry.Settings, vm.menu())
            assertEquals(onChannel, vm.snapshot.value.onChannel)
        }

    @Test
    fun selectOnEachRowOfTheSheetOpensChannelsOpensSettingsOrLeaves() =
        guideTest { vm ->
            val got = eventsOf(vm)
            vm.open(CH5)
            runCurrent()
            vm.select()
            runCurrent()
            assertEquals(listOf(GuideEvent.Tune(CH5)), got)
            got.clear()
            assertTrue(vm.back())
            vm.select()
            runCurrent()
            assertEquals(listOf(GuideEvent.OpenChannels), got)
            got.clear()
            vm.down()
            vm.select()
            runCurrent()
            assertEquals(listOf(GuideEvent.OpenSettings), got)
            got.clear()
            vm.down()
            vm.select()
            runCurrent()
            assertEquals(listOf(GuideEvent.Exit), got)
        }

    @Test
    fun theGuideAfterManagingShowsTheNewLineup() =
        guideTest { vm ->
            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
            vm.open(CH7)
            runCurrent()
            vm.toMenu()
            vm.channelsOpened()
            runCurrent()
            assertEquals(
                3,
                vm
                    .ready()
                    .model.rows.size,
            )

            // The viewer adds a channel while the screen shows.
            val added = ChannelId("ch-3")
            fake.channel(added, 3, halfHours(600))
            lineupList.list.value = lineupList.list.value + summary(added, 3)
            runCurrent()
            vm.channelsClosed()
            runCurrent()

            assertEquals(
                4,
                vm
                    .ready()
                    .model.rows.size,
            )
            assertEquals(GuideMenuEntry.Channels, vm.menu())
        }

    @Test
    fun closingTheSheetWhileTheGridLoadsAgainReturnsToTheCellOnTheGridShown() =
        guideTest { vm ->
            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
            vm.open(CH7)
            runCurrent()
            vm.toMenu()
            vm.channelsOpened()
            runCurrent()
            val measured = vm.windowMs
            fake.channel(ChannelId("ch-3"), 3, halfHours(600))
            lineupList.list.value = lineupList.list.value + summary(ChannelId("ch-3"), 3)
            runCurrent()

            vm.channelsClosed()

            // The grid shown stays until the new one lands: closing the sheet works on it, and the new grid keeps the
            // focus.
            assertTrue(vm.toggleMenu())
            assertNull(vm.menu())
            assertEquals(CH7, vm.focused().channel)
            // The opening's figures are the first session's, not the reload's.
            assertEquals(measured, vm.windowMs)
            runCurrent()
            assertEquals(
                4,
                vm
                    .ready()
                    .model.rows.size,
            )
            assertEquals(CH7, vm.focused().channel)
        }

    @Test
    fun resetThenBackAtOnceStillUpdatesTheGridWhenTheWriteLandsLater() =
        guideTest { vm ->
            closedWithNoChange(vm)
            after(10_000)

            addChannelThree()
            runCurrent()

            assertEquals(
                4,
                vm
                    .ready()
                    .model.rows.size,
            )
            // On the button it was left on, it stays there.
            assertEquals(GuideMenuEntry.Channels, vm.menu())
        }

    @Test
    fun aStaleGridOfTheOldSessionIsNotPublishedAfterAReload() =
        guideTest { vm ->
            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
            val oldRead = CompletableDeferred<Unit>()
            val newRead = CompletableDeferred<Unit>()
            schedule.cachedGate = oldRead
            vm.open(CH7)
            runCurrent()
            // The screen opens and the lineup changes under it while the first read is out.
            vm.channelsOpened()
            lineupList.list.value = lineupList.list.value + summary(ChannelId("ch-3"), 3)
            runCurrent()
            var fired = false
            schedule.afterSchedule = {
                if (!fired) {
                    fired = true
                    // The old read has its answer (three channels) and is about to build; the reload comes first.
                    fake.channel(ChannelId("ch-3"), 3, halfHours(600))
                    schedule.cachedGate = newRead
                    vm.channelsClosed()
                }
            }

            oldRead.complete(Unit)
            runCurrent()

            assertTrue("the old read's grid was published", vm.snapshot.value.state !is GuideState.Ready)
            newRead.complete(Unit)
            runCurrent()
            assertEquals(
                4,
                vm
                    .ready()
                    .model.rows.size,
            )
        }

    // The guide on channel 7, the screen opened and closed with no change, and the viewer on channel 7's row again.
    private fun TestScope.closedWithNoChange(vm: GuideViewModel) {
        lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
        vm.open(CH7)
        runCurrent()
        vm.toMenu()
        vm.channelsOpened()
        runCurrent()
        vm.channelsClosed()
        runCurrent()
    }

    private fun addChannelThree() {
        val added = ChannelId("ch-3")
        fake.channel(added, 3, halfHours(600))
        lineupList.list.value = lineupList.list.value + summary(added, 3)
    }

    @Test
    fun aChangeLongAfterTheScreenClosedReloadsTheGridWithTheFocusWhereItWas() =
        guideTest { vm ->
            closedWithNoChange(vm)
            assertTrue(vm.toggleMenu())
            val focus = vm.focused()
            after(60_000)

            addChannelThree()
            runCurrent()

            assertEquals(
                4,
                vm
                    .ready()
                    .model.rows.size,
            )
            assertNull(vm.menu())
            assertEquals(focus.channel, vm.focused().channel)
            assertEquals(focus.cellStartMs, vm.focused().cellStartMs)
        }

    @Test
    fun aChangeWhileOnACellKeepsThatCellFocused() =
        guideTest { vm ->
            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
            vm.open(CH7)
            runCurrent()
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            val focus = vm.focused()

            addChannelThree()
            runCurrent()

            assertEquals(
                4,
                vm
                    .ready()
                    .model.rows.size,
            )
            assertEquals(focus.channel, vm.focused().channel)
            assertEquals(focus.cellStartMs, vm.focused().cellStartMs)
        }

    @Test
    fun aReloadWhoseFirstReadIsEmptyKeepsTheGridAndTheFocus() =
        guideTest { vm ->
            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
            vm.open(CH7)
            runCurrent()
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            val gate = CompletableDeferred<Unit>()
            schedule.cachedAnswer = ScheduleWindow(emptyList(), complete = false)
            schedule.asNeededGate = gate

            addChannelThree()
            runCurrent()

            // The old grid stays, and Back still goes to the playing cell.
            assertEquals(
                3,
                vm
                    .ready()
                    .model.rows.size,
            )
            assertTrue(vm.back())
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            val focus = vm.focused()
            schedule.cachedAnswer = null
            gate.complete(Unit)
            runCurrent()

            assertEquals(
                4,
                vm
                    .ready()
                    .model.rows.size,
            )
            assertEquals(focus.channel, vm.focused().channel)
            assertEquals(focus.cellStartMs, vm.focused().cellStartMs)
        }

    @Test
    fun aFinalEmptyBuildAfterAReloadShowsEmpty() =
        guideTest { vm ->
            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
            vm.open(CH7)
            runCurrent()
            schedule.cachedAnswer = ScheduleWindow(emptyList(), complete = false)
            schedule.asNeededAnswer = ScheduleWindow(emptyList(), complete = true)

            addChannelThree()
            runCurrent()

            assertEquals(GuideState.Empty, vm.snapshot.value.state)
        }

    @Test
    fun aReloadWhoseFirstReadHasNoAnswerIsLeftToTheBuild() =
        guideTest { vm ->
            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
            vm.open(CH7)
            runCurrent()
            schedule.cachedNull = true
            addChannelThree()
            runCurrent()

            // The build ran in its place and its rows are shown.
            assertEquals(
                4,
                vm
                    .ready()
                    .model.rows.size,
            )
        }

    @Test
    fun theMenuKeepsTheFocusAcrossTheClockUnderTheScreenAndAfter() =
        guideTest { vm ->
            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
            vm.open(CH5)
            runCurrent()
            vm.toMenu()
            vm.channelsOpened()
            val opened = vm.ready().nowMs
            after(3 * MIN)
            assertEquals(GuideMenuEntry.Channels, vm.menu())
            // The clock stands still under the screen.
            assertEquals(opened, vm.ready().nowMs)

            vm.channelsClosed()
            runCurrent()
            assertEquals(GuideMenuEntry.Channels, vm.menu())
            // And runs again at once when it closes.
            assertTrue(vm.ready().nowMs >= opened + 3 * MIN)
            after(2 * MIN)
            assertEquals(GuideMenuEntry.Channels, vm.menu())
            assertTrue(vm.ready().nowMs >= opened + 5 * MIN)
        }

    @Test
    fun aRenameIsNotAChangeToTheLineup() =
        guideTest { vm ->
            closedWithNoChange(vm)
            val calls = schedule.calls

            lineupList.list.value = lineupList.list.value.map { it.copy(name = "Renamed ${it.name}") }
            runCurrent()

            assertEquals(calls, schedule.calls)
            assertEquals(GuideMenuEntry.Channels, vm.menu())
        }

    @Test
    fun aLineupThatDidNotChangeLeavesTheGuideAsItWas() =
        guideTest { vm ->
            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
            vm.open(CH7)
            runCurrent()
            vm.toMenu()
            vm.channelsOpened()
            runCurrent()
            val calls = schedule.calls
            val before = vm.ready()

            vm.channelsClosed()
            runCurrent()

            assertEquals(calls, schedule.calls)
            assertEquals(before, vm.ready())
            assertEquals(GuideMenuEntry.Channels, vm.menu())
        }

    @Test
    fun theDetailsPanelOnTheMenuFollowsAChannelRenumberedUnderTheScreen() =
        guideTest { vm ->
            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
            vm.open(CH7)
            runCurrent()
            vm.toMenu()
            vm.channelsOpened()
            runCurrent()
            assertEquals(7, vm.panel.value?.channelNumber)

            // Channel 7 is renumbered to 20 while the screen shows; 7 is free.
            fake.channel(CH7, 20, halfHours(600))
            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 20), summary(CH9, 9))
            runCurrent()
            vm.channelsClosed()
            runCurrent()

            assertEquals(GuideMenuEntry.Channels, vm.menu())
            val row =
                vm
                    .ready()
                    .model.rows
                    .single { it.channel == vm.focused().channel }
            assertEquals(20, row.number)
            assertEquals(row.number, vm.panel.value?.channelNumber)
            assertEquals(row.name, vm.panel.value?.channelName)
        }
}
