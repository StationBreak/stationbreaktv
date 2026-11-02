// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program

/** A lineup change from the channels screen reaches flipping at once (spec 21, F2.4). */
class FlipFollowsLineupTest {
    private fun Rig.publishSchedule() {
        val lineup = schedule.channels.map { it.key to it.value.number }
        channels.publish(*lineup.toTypedArray())
    }

    @Test
    fun aFlipAfterSwitchOffSkipsTheChannel() =
        runTest {
            val f = flipping()
            f.r.schedule.channels
                .remove(CH9)
            f.r.publishSchedule()
            runCurrent()
            f.up()
            // 5 was playing; 9 is off, so Up wraps to 2.
            assertEquals(LiveState.Choosing(ChannelTag(2, "Test Channel 2"), overPicture = true), f.r.state)
        }

    @Test
    fun aFlipAfterRenumberGoesInTheNewOrder() =
        runTest {
            val f = flipping()
            // Channel 9 becomes 3: Down from 5 now goes to 3.
            f.r.schedule.channel(CH9, 3, listOf(program(0, 600, 9)))
            f.r.publishSchedule()
            runCurrent()
            f.down()
            assertEquals(LiveState.Choosing(ChannelTag(3, "Test Channel 3"), overPicture = true), f.r.state)
        }

    @Test
    fun aFlipAlreadyMovingKeepsItsOrder() =
        runTest {
            val f = flipping()
            f.up()
            // Channel 2 becomes 20 while the flip moves: the new order would step 9 -> 20, the old one 9 -> 2.
            f.r.schedule.channel(CH2, 20, listOf(program(0, 600, 2)))
            f.r.publishSchedule()
            runCurrent()
            f.up()
            assertEquals(LiveState.Choosing(ChannelTag(2, "Test Channel 2"), overPicture = true), f.r.state)
        }
}
