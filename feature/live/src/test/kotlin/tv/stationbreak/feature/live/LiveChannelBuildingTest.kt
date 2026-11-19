// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.FIRST_LINEUP_WAIT
import tv.stationbreak.core.domain.lineup.FirstLineupState
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program

/**
 * Live TV while the first lineup is still being built (just after setup): Stan stands by, never the no-channels card,
 * and a channel opens as soon as one is on the air. A settled lineup with nothing on the air shows no channels, and so
 * does a first lineup that is still not settled after [FIRST_LINEUP_WAIT]. The first tune never waits on any of it.
 */
class LiveChannelBuildingTest {
    @Test
    fun whileTheFirstLineupIsBuiltItStandsByNotNoChannels() =
        runTest {
            val r = Rig(this)
            r.firstLineup.state.value = FirstLineupState.Building
            r.start()

            assertEquals(LiveState.BuildingChannels, r.state)
            assertTrue(r.seen.none { it == LiveState.NoChannels })
            assertTrue(r.tune.calls.isEmpty())
        }

    @Test
    fun aChannelOnTheAirOpensStraightFromStandingBy() =
        runTest {
            val r = Rig(this)
            r.firstLineup.state.value = FirstLineupState.Building
            r.start()

            r.schedule.channel(CH7, 7, listOf(program(0, 60, 1)))
            r.channels.publish(CH7 to 7)
            r.at(1_000)

            assertIs<LiveState.Tuning>(r.seen.first { it is LiveState.Tuning })
            assertEquals(itemOf(1), r.tunes.single().item)
            assertTrue(r.seen.none { it == LiveState.NoChannels })
        }

    @Test
    fun aSettledLineupChoosesAgainAndShowsNoChannelsWhenNothingAirs() =
        runTest {
            val r = Rig(this)
            r.firstLineup.state.value = FirstLineupState.Building
            r.start()
            val looks = r.schedule.airingsCalls

            r.firstLineup.state.value = FirstLineupState.Settled
            r.at(1_000)

            assertEquals(LiveState.NoChannels, r.state)
            assertEquals("the change looked at the schedule again", looks + 1, r.schedule.airingsCalls)
        }

    @Test
    fun aFirstLineupThatNeverSettlesShowsNoChannelsAfterTheWait() =
        runTest {
            val r = Rig(this)
            r.firstLineup.state.value = FirstLineupState.Building
            r.start()

            r.at(FIRST_LINEUP_WAIT.inWholeMilliseconds - 1)
            assertEquals(LiveState.BuildingChannels, r.state)

            r.at(FIRST_LINEUP_WAIT.inWholeMilliseconds)
            assertEquals("live TV never stands by for good", LiveState.NoChannels, r.state)
        }

    @Test
    fun theFirstTuneDoesNotLookAtTheLineupState() =
        runTest {
            val r = Rig(this)
            r.firstLineup.state.value = FirstLineupState.Building
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 1)))
            r.start()

            assertEquals(itemOf(1), r.tunes.single().item)
            assertEquals("a channel on the air is tuned at once", 0, r.firstLineup.asked)
        }
}
