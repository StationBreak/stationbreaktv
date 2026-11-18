// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.LineupState
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program

/**
 * Live TV while the lineup is still being built (the first one after setup): Stan stands by, never the no-channels
 * card, and a channel opens as soon as one is on the air. Only a lineup built with no channels shows no channels.
 */
class LiveChannelBuildingTest {
    @Test
    fun whileTheFirstLineupIsBuiltItStandsByNotNoChannels() =
        runTest {
            val r = Rig(this)
            r.readiness.state.value = LineupState.Building
            r.start()

            assertEquals(LiveState.BuildingChannels, r.state)
            assertTrue(r.seen.none { it == LiveState.NoChannels })
            assertEquals("the signed-in account's lineup", listOf(r.account), r.readiness.asked.distinct())
            assertTrue(r.tune.calls.isEmpty())
        }

    @Test
    fun aLineupBuiltWithChannelsOpensOneStraightFromStandingBy() =
        runTest {
            val r = Rig(this)
            r.readiness.state.value = LineupState.Building
            r.start()

            r.schedule.channel(CH7, 7, listOf(program(0, 60, 1)))
            r.channels.publish(CH7 to 7)
            r.readiness.state.value = LineupState.Ready
            r.at(1_000)

            assertIs<LiveState.Tuning>(r.seen.first { it is LiveState.Tuning })
            assertEquals(itemOf(1), r.tunes.single().item)
            assertTrue(r.seen.none { it == LiveState.NoChannels })
        }

    @Test
    fun aLineupBuiltWithNoChannelsShowsNoChannels() =
        runTest {
            val r = Rig(this)
            r.readiness.state.value = LineupState.Building
            r.start()

            r.readiness.state.value = LineupState.Empty
            r.at(1_000)

            assertEquals(LiveState.NoChannels, r.state)
        }

    @Test
    fun aReadyLineupLiveCannotTuneYetStillStandsBy() =
        runTest {
            // The lineup says a channel is on the air, but the channel list has not caught up: no false "no channels".
            val r = Rig(this)
            r.readiness.state.value = LineupState.Ready
            r.start()

            assertEquals(LiveState.BuildingChannels, r.state)
        }
}
