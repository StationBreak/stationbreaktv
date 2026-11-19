// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program

/**
 * Live TV before anything is chosen: Stan stands by ([LiveState.Starting]), never the off-air card, which would tell
 * the viewer the station is closed when the app is only starting.
 */
class LiveChannelStartingTest {
    @Test
    fun aFreshChannelStartsStandingByNotOffTheAir() =
        runTest {
            val r = Rig(this)
            assertEquals(LiveState.Starting, r.state)
        }

    @Test
    fun theFirstTuneFollowsStandingByWithNoOffAirCardBetween() =
        runTest {
            val r = Rig(this)
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 1)))
            r.start()
            r.at(1_000)

            assertEquals(LiveState.Starting, r.seen.first())
            val beforeTune = r.seen.takeWhile { it !is LiveState.Tuning }
            assertTrue("no card before the tune: $beforeTune", beforeTune.none { it is LiveState.Ident })
            assertTrue("the first channel tunes: ${r.seen}", r.seen.any { it is LiveState.Tuning })
        }
}
