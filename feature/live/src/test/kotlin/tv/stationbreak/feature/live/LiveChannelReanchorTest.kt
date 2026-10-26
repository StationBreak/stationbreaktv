// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.HOUR
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.slate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** When a sitting goes back to the clock: a drift past the schedule built ahead, and a tuner stopped under the run. */
class LiveChannelReanchorTest {
    private fun Rig.ordinaryDay() {
        schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2), slate(120, 180), program(180, 240, 3)))
    }

    @Test
    fun driftReanchorsWhenItPassesTheScheduleBuiltAhead() =
        runTest { driftReachesTheEndOfTheBuiltDays(dayHours = 24, thirdStartHour = 47, fourthStartHour = 49) }

    @Test
    fun driftUsesTheChannelsOwnDayLengthForTheBuiltAheadLimit() =
        // Six-hour days: the schedule is built to hour 12, so a fourth block at hour 13 is past it.
        runTest { driftReachesTheEndOfTheBuiltDays(dayHours = 6, thirdStartHour = 11, fourthStartHour = 13) }

    private suspend fun TestScope.driftReachesTheEndOfTheBuiltDays(
        dayHours: Long,
        thirdStartHour: Long,
        fourthStartHour: Long,
    ) {
        val r = Rig(this, startMin = 40)
        // Block 2 is long, so a drifted sitting reaches the end of day n+1 by the fourth block.
        r.schedule
            .channel(
                CH7,
                7,
                listOf(
                    program(0, 60, 1),
                    program(60, thirdStartHour * 60, 2),
                    program(thirdStartHour * 60, fourthStartHour * 60, 3),
                    program(fourthStartHour * 60, (fourthStartHour + 1) * 60, 4),
                ),
            ).epochMs = dayHours * HOUR
        r.tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead())
        r.start()
        r.at(3_000)
        assertEquals(Call.Tune(itemOf(2), Duration.ZERO, 3_000), r.tunes.last())

        // The second show is joined from its start, 19 min 57 s early (the dead first show had 20 min left).
        val driftMs = 60 * MIN - (40 * MIN + 3_000)

        fun wall(minutes: Long) = minutes * MIN - 40 * MIN - driftMs
        r.at(wall(thirdStartHour * 60))
        val thirdShow =
            r.tune.calls
                .filterIsInstance<Call.TuneHandle>()
                .single()
        assertEquals(Call.TuneHandle(itemOf(3), Duration.ZERO, wall(thirdStartHour * 60)), thirdShow)

        // The fourth block starts past the end of day n+1: re-anchor to the clock under the snow.
        r.at(wall(fourthStartHour * 60))
        assertTrue(r.tune.calls.any { it is Call.Discard && it.item == itemOf(4) })
        val last = r.tunes.last()
        assertEquals(itemOf(3), last.item)
        // The clock reads the fourth start minus the drift: 1 h 40 min 3 s into the third block.
        assertEquals(100.minutes + 3.seconds, last.start)
        assertIs<LiveState.Tuning>(r.seen.last { it is LiveState.Tuning })
    }

    @Test
    fun anUnexpectedCancelledTuneReanchorsInsteadOfReturning() =
        runTest {
            val r = Rig(this)
            r.ordinaryDay()
            r.tune.script(itemOf(1), { TuneOutcome.Cancelled })
            val job = r.start()
            r.at(2_000)
            // The run is still alive, and a fresh sitting tuned the item again at the clock position, under the snow.
            assertTrue(job.isActive)
            assertEquals(2, r.tunes.size)
            assertEquals(itemOf(1), r.tunes.last().item)
            assertEquals(10.minutes + 500.milliseconds, r.tunes.last().start)
            assertTrue(r.seen.any { it is LiveState.Tuning })
            assertIs<LiveState.Watching>(r.state)
        }
}
