// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.hours

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HoursScheduleTest {
    private fun h(
        hour: Int,
        minute: Int = 0,
    ) = hour * 60 + minute

    private fun period(
        day: Int,
        from: Int,
        to: Int,
        level: AudienceLevel,
    ) = HoursPeriod(day, from, to, level)

    @Test
    fun emptyScheduleSetsNothing() {
        assertNull(HoursSchedule.EMPTY.levelAt(MONDAY, 0))
    }

    @Test
    fun aPeriodCoversItsStartUpToButNotItsEnd() {
        val s = HoursSchedule(listOf(period(MONDAY, h(8), h(10), AudienceLevel.Kids)))
        assertNull(s.levelAt(MONDAY, h(8) - 1))
        assertEquals(AudienceLevel.Kids, s.levelAt(MONDAY, h(8)))
        assertEquals(AudienceLevel.Kids, s.levelAt(MONDAY, h(10) - 1))
        assertNull(s.levelAt(MONDAY, h(10)))
        assertNull(s.levelAt(TUESDAY, h(9)))
    }

    @Test
    fun aPeriodMayEndAtMidnight() {
        val s = HoursSchedule(listOf(period(MONDAY, h(22), 1440, AudienceLevel.Teen)))
        assertEquals(AudienceLevel.Teen, s.levelAt(MONDAY, 1439))
        assertNull(s.levelAt(TUESDAY, 0))
    }

    @Test
    fun anOvernightPeriodRunsIntoTheNextDay_includingAcrossTheWeekEnd() {
        val s = HoursSchedule(listOf(period(SUNDAY, h(22), h(6), AudienceLevel.OffAir)))
        assertNull(s.levelAt(SUNDAY, h(21, 59)))
        assertEquals(AudienceLevel.OffAir, s.levelAt(SUNDAY, h(22)))
        assertEquals(AudienceLevel.OffAir, s.levelAt(MONDAY, h(5, 59)))
        assertNull(s.levelAt(MONDAY, h(6)))
        assertNull(s.levelAt(SATURDAY, h(3)))
    }

    @Test
    fun overlappingPeriodsResolveToTheMostRestrictive() {
        val s =
            HoursSchedule(
                listOf(
                    period(FRIDAY, h(8), h(20), AudienceLevel.Family),
                    period(FRIDAY, h(12), h(14), AudienceLevel.Kids),
                    period(FRIDAY, h(13), h(15), AudienceLevel.Adult),
                    period(FRIDAY, h(18), h(19), AudienceLevel.OffAir),
                ),
            )
        assertEquals(AudienceLevel.Family, s.levelAt(FRIDAY, h(9)))
        assertEquals(AudienceLevel.Kids, s.levelAt(FRIDAY, h(13, 30)))
        assertEquals(AudienceLevel.Family, s.levelAt(FRIDAY, h(14, 30)))
        assertEquals(AudienceLevel.OffAir, s.levelAt(FRIDAY, h(18, 30)))
    }

    @Test
    fun adjacentPeriodsMeetWithNoGapAndNoOverlap() {
        val s =
            HoursSchedule(
                listOf(
                    period(MONDAY, h(8), h(12), AudienceLevel.Kids),
                    period(MONDAY, h(12), h(16), AudienceLevel.Teen),
                ),
            )
        assertEquals(AudienceLevel.Kids, s.levelAt(MONDAY, h(12) - 1))
        assertEquals(AudienceLevel.Teen, s.levelAt(MONDAY, h(12)))
    }

    @Test
    fun anEmptyOrOutOfRangePeriodIsRefused() {
        assertFailsWith<IllegalArgumentException> { period(MONDAY, h(8), h(8), AudienceLevel.Kids) }
        assertFailsWith<IllegalArgumentException> { period(MONDAY, -1, h(8), AudienceLevel.Kids) }
        assertFailsWith<IllegalArgumentException> { period(MONDAY, 1440, h(8), AudienceLevel.Kids) }
        assertFailsWith<IllegalArgumentException> { period(MONDAY, h(8), 1441, AudienceLevel.Kids) }
        assertFailsWith<IllegalArgumentException> { period(0, h(8), h(9), AudienceLevel.Kids) }
        assertFailsWith<IllegalArgumentException> { period(8, h(8), h(9), AudienceLevel.Kids) }
    }

    @Test
    fun anyScheduleAnswersTheMostRestrictiveOfTheCoveringPeriods_overRandomSchedules() {
        val random = Random(789)
        repeat(200) { _ ->
            val periods =
                List(random.nextInt(0, 6)) { _ ->
                    var from: Int
                    var to: Int
                    do {
                        from = random.nextInt(0, 1440)
                        to = random.nextInt(0, 1441)
                    } while (from == to)
                    period(random.nextInt(1, 8), from, to, AudienceLevel.entries.random(random))
                }
            val s = HoursSchedule(periods)
            val day = random.nextInt(1, 8)
            val minute = random.nextInt(0, 1440)
            val covering = periods.filter { p -> p.covers(day, minute) }.map { p -> p.level }
            assertEquals(covering.maxOrNull(), s.levelAt(day, minute))
        }
    }
}
