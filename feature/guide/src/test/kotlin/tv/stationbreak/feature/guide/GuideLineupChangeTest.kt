// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
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
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleWindow
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Instant

/** What the guide does when channel management changes the lineup under it (#556, #557). */
class GuideLineupChangeTest : GuideRig() {
    private fun GuideViewModel.toMenu() {
        while (snapshot.value.menu == null) check(back()) { "Back did not reach the menu" }
    }

    private fun TestScope.eventsOf(vm: GuideViewModel): MutableList<GuideEvent> =
        mutableListOf<GuideEvent>().also { got -> backgroundScope.launch { vm.events.collect { got += it } } }

    private fun withNewOrderOn(
        id: ChannelId,
        key: String,
    ) = lineupList.list.value.map { if (it.id == id) it.copy(playKey = PlayKey(key)) else it }

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

            lineupList.list.value = withNewOrderOn(CH9, "changed")
            runCurrent()
            vm.channelsClosed()
            runCurrent()

            assertTrue("the grid was not read again", schedule.calls > calls)
            assertEquals(GuideMenuEntry.Channels, vm.snapshot.value.menu)
        }

    // The day rebuild after a save can finish after the guide reloaded: the grid ends on the new order.
    @Test
    fun aReloadWhileTheDaysAreRebuiltEndsOnTheNewOrder() =
        guideTest { vm ->
            lineupList.list.value = listOf(summary(CH5, 5), summary(CH7, 7), summary(CH9, 9))
            vm.open(CH7)
            runCurrent()
            vm.channelsOpened()
            runCurrent()
            val rebuilt = CompletableDeferred<Unit>()
            // The new order's days are not built yet: nothing kept for them, and a read that builds waits for it.
            schedule.cachedAnswer = ScheduleWindow(emptyList(), complete = false)
            schedule.asNeededGate = rebuilt

            lineupList.list.value = withNewOrderOn(CH9, "new")
            runCurrent()
            vm.channelsClosed()
            runCurrent()
            schedule.cachedAnswer = null
            fake.channel(CH9, 9, (0L until 600L step 30L).map { program(it, it + 30, 900 + (it / 30).toInt()) })
            rebuilt.complete(Unit)
            runCurrent()

            val first =
                vm
                    .ready()
                    .model.rows
                    .first { it.channel == CH9 }
                    .cells
                    .first()
            assertTrue(
                "the old order is still shown: ${first.itemId}",
                first.itemId
                    ?.value
                    ?.toInt(HEX)
                    ?.let { it >= 900 } == true,
            )
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

    private companion object {
        const val HEX = 16
    }
}
