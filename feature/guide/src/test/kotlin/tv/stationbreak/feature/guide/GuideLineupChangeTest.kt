// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
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

/** What the guide does when channel management changes the lineup under it (#556, #557). */
class GuideLineupChangeTest : GuideRig() {
    private fun GuideViewModel.toMenu() {
        while (snapshot.value.menu == null) check(back()) { "Back did not reach the menu" }
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
    )

    // #556: a save that only changes the play order, and a reshuffle, move neither a channel nor its number.
    @Test
    fun aChangeOfPlayOrderAloneReloadsTheGridWhenManagingCloses() =
        guideTest { vm ->
            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
            vm.open(CH7)
            runCurrent()
            vm.toMenu()
            vm.channelsOpened()
            runCurrent()
            val calls = schedule.calls

            lineupList.list.value = lineupList.list.value.map { if (it.id == CH9) it.copy(playKey = 42) else it }
            runCurrent()
            vm.channelsClosed()
            runCurrent()

            assertTrue("the grid was not read again", schedule.calls > calls)
            assertEquals(GuideMenuEntry.Channels, vm.snapshot.value.menu)
        }

    // #557: the playing channel is deleted while Channels shows; cable tunes the next one up, or down from the top.
    private fun TestScope.closingAfterRemoving(
        vm: GuideViewModel,
        playing: ChannelId,
    ): List<GuideEvent> {
        lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
        val got = eventsOf(vm)
        vm.open(playing)
        runCurrent()
        vm.toMenu()
        vm.channelsOpened()
        runCurrent()
        lineupList.list.value = lineupList.list.value.filter { it.id != playing }
        runCurrent()
        vm.channelsClosed()
        runCurrent()
        return got
    }

    @Test
    fun deletingThePlayingChannelTunesTheNextHigherNumberOnLeavingChannels() =
        guideTest { vm ->
            assertEquals(listOf(GuideEvent.Tune(CH9)), closingAfterRemoving(vm, CH7))
        }

    @Test
    fun deletingTheLowestPlayingChannelTunesTheNextHigherNotTheTop() =
        guideTest { vm ->
            assertEquals(listOf(GuideEvent.Tune(CH7)), closingAfterRemoving(vm, CH5))
        }

    @Test
    fun deletingTheHighestPlayingChannelTunesTheNextLower() =
        guideTest { vm ->
            assertEquals(listOf(GuideEvent.Tune(CH7)), closingAfterRemoving(vm, CH9))
        }

    @Test
    fun leavingChannelsWithThePlayingChannelStillThereTunesNothing() =
        guideTest { vm ->
            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
            val got = eventsOf(vm)
            vm.open(CH7)
            runCurrent()
            vm.channelsOpened()
            runCurrent()
            lineupList.list.value = lineupList.list.value.filter { it.id != CH9 }
            runCurrent()
            vm.channelsClosed()
            runCurrent()
            assertTrue(got.isEmpty())
        }
}
