// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.test.runTest
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** [firstOnAir]: the one rule for "a channel is on the air", shared by live TV's first tune and setup's cheer. */
class FirstOnAirTest {
    private val schedule = FakeSchedule()
    private val at = Instant.fromEpochMilliseconds(T0 + 30 * MIN)

    @Test
    fun theFirstVisibleChannelWithABlockNowInNumberOrder() =
        runTest {
            schedule.channel(CH5, 5, listOf(program(0, 60, 1)), hidden = HiddenReason.EmptyToday)
            schedule.channel(CH7, 7, listOf(program(60, 120, 2)))
            schedule.channel(CH9, 9, listOf(program(0, 60, 3), program(60, 120, 4)))

            val found = schedule.firstOnAir(at)

            assertEquals(CH9, found?.channel?.id)
            assertEquals(listOf(CH7 to TUNE_LOOK_AHEAD, CH9 to TUNE_LOOK_AHEAD), asked())
        }

    @Test
    fun theCallersOrderComesFirst() =
        runTest {
            schedule.channel(CH7, 7, listOf(program(0, 60, 1)))
            schedule.channel(CH9, 9, listOf(program(0, 60, 2)))

            assertEquals(CH9, schedule.firstOnAir(at) { visible -> visible.reversed() }?.channel?.id)
        }

    @Test
    fun nothingOnTheAirIsNull() =
        runTest {
            schedule.channel(CH7, 7, listOf(program(60, 120, 1)))

            assertNull(schedule.firstOnAir(at))
        }

    private val lookAheads = mutableListOf<Int>()

    init {
        schedule.onAirObserver = { lookAheads += it }
    }

    private fun asked() = schedule.onAirCalls.map { it.first }.zip(lookAheads)
}
