// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelStatus
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Instant

/** The Channels button above the grid (spec 21, F2.4): Up from the top row reaches it, and Select opens management. */
class GuideChannelsButtonTest : GuideRig() {
    private fun GuideViewModel.up(
        repeat: Boolean = false,
        atMs: Long = 0,
    ): Unit = move(GuideDirection.Up, repeat, atMs)

    private fun GuideViewModel.down(
        repeat: Boolean = false,
        atMs: Long = 0,
    ): Unit = move(GuideDirection.Down, repeat, atMs)

    private fun GuideViewModel.onButton(): Boolean = snapshot.value.onButton

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
    )

    @Test
    fun upFromTheTopRowGoesToTheButton() =
        guideTest { vm ->
            vm.open(CH5)
            runCurrent()
            val cell = vm.focused()
            vm.up()
            assertTrue(vm.onButton())
            // The cell last focused is kept for Down to return to.
            assertEquals(cell, vm.focused())
        }

    @Test
    fun aHeldUpWaitsAtTheTopRowBeforeTheButton() =
        guideTest { vm ->
            vm.open(CH5)
            runCurrent()
            vm.up(repeat = true, atMs = 1_000)
            assertFalse(vm.onButton())
            vm.up(repeat = true, atMs = 1_399)
            assertFalse(vm.onButton())
            vm.up(repeat = true, atMs = 1_000 + WRAP_HOLD.inWholeMilliseconds)
            assertTrue(vm.onButton())
        }

    @Test
    fun upNoLongerWrapsFromTheTop() =
        guideTest { vm ->
            vm.open(CH5)
            runCurrent()
            vm.up()
            vm.up()
            vm.up()
            assertTrue(vm.onButton())
            assertEquals(CH5, vm.focused().channel)
        }

    @Test
    fun downStillWrapsFromTheBottom() =
        guideTest { vm ->
            vm.open(CH9)
            runCurrent()
            vm.down()
            assertEquals(CH5, vm.focused().channel)
            assertFalse(vm.onButton())
        }

    @Test
    fun downFromTheButtonReturnsToTheLastCell() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            vm.up()
            val cell = vm.focused()
            assertEquals(CH5, cell.channel)
            vm.up()
            assertTrue(vm.onButton())
            vm.down()
            assertFalse(vm.onButton())
            assertEquals(cell, vm.focused())
        }

    @Test
    fun selectOnTheButtonOpensChannels() =
        guideTest { vm ->
            val got = eventsOf(vm)
            vm.open(CH5)
            runCurrent()
            vm.select()
            runCurrent()
            assertEquals(listOf(GuideEvent.Tune(CH5)), got)
            got.clear()
            vm.up()
            vm.select()
            runCurrent()
            assertEquals(listOf(GuideEvent.OpenChannels), got)
        }

    @Test
    fun backOnTheButtonGoesToThePlayingCell() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            val playing = vm.focused()
            vm.up()
            vm.up()
            assertTrue(vm.onButton())
            assertTrue(vm.backToPlaying())
            assertFalse(vm.onButton())
            assertEquals(playing, vm.focused())
        }

    @Test
    fun backOnTheButtonWithNothingPlayingGoesOnToTheActivity() =
        guideTest { vm ->
            vm.open(null)
            runCurrent()
            vm.up()
            assertTrue(vm.onButton())
            assertFalse(vm.backToPlaying())
            assertTrue(vm.onButton())
        }

    @Test
    fun upOnTheButtonDoesNothing() =
        guideTest { vm ->
            vm.open(CH5)
            runCurrent()
            vm.up()
            val before = vm.snapshot.value
            vm.up()
            vm.move(GuideDirection.Left, repeat = false, atMs = 0)
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            assertEquals(before, vm.snapshot.value)
        }

    @Test
    fun theGuideAfterManagingShowsTheNewLineup() =
        guideTest { vm ->
            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
            vm.open(CH7)
            runCurrent()
            vm.up()
            vm.up()
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
            assertTrue(vm.onButton())
        }

    @Test
    fun aLineupThatDidNotChangeLeavesTheGuideAsItWas() =
        guideTest { vm ->
            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
            vm.open(CH7)
            runCurrent()
            vm.up()
            vm.up()
            vm.channelsOpened()
            runCurrent()
            val calls = schedule.calls
            val before = vm.ready()

            vm.channelsClosed()
            runCurrent()

            assertEquals(calls, schedule.calls)
            assertEquals(before, vm.ready())
            assertTrue(vm.onButton())
        }

    @Test
    fun anEmptyGuideStillReachesTheButtonWithUp() =
        guideTest { vm ->
            fake.channels.clear()
            vm.open(CH5)
            runCurrent()
            assertEquals(GuideState.Empty, vm.snapshot.value.state)
            vm.up()
            assertTrue(vm.onButton())
        }
}
