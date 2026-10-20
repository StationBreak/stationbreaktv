// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import tv.stationbreak.core.scheduler.vectors.VectorFiles
import tv.stationbreak.core.scheduler.vectors.VectorJson
import tv.stationbreak.core.scheduler.vectors.VectorJson.array
import tv.stationbreak.core.scheduler.vectors.VectorJson.obj
import tv.stationbreak.core.scheduler.vectors.VectorJson.objects
import tv.stationbreak.core.scheduler.vectors.VectorJson.string
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `n` and `epochStart(n)` against docs/rules-format.md ("Timing"), with cases worked by hand:
 *
 * ```
 * n             = floor((t + offsetMs - startMs) / epochMs)
 * epochStart(n) = n * epochMs + startMs - offsetMs
 * ```
 */
class EpochMathTest {
    // UTC-5, day starts 06:00 local = 11:00Z. The epoch containing 1970-01-01T00:00Z began 1969-12-31T11:00Z.
    private val eastern = testSettings(utcOffsetMinutes = -300, startMinuteOfDay = 360)

    // Nepal UTC+5:45, day starts 18:30 local = 12:45Z.
    private val nepal = testSettings(utcOffsetMinutes = 345, startMinuteOfDay = 1110)

    @Test
    fun cutoffIsStartMinusGrace() {
        val s = testSettings(utcOffsetMinutes = -300, startMinuteOfDay = 360, graceHours = 6)
        for (n in listOf(-3L, 0L, 19_000L)) {
            assertEquals(Epochs.epochStart(s, n) - 6 * MS_HOUR, Epochs.cutoff(s, n))
        }
        val noGrace = testSettings(graceHours = 0)
        assertEquals(Epochs.epochStart(noGrace, 5), Epochs.cutoff(noGrace, 5))
    }

    @Test
    fun isAirableIsInclusiveAtBothEnds() {
        val s = testSettings(minRuntimeMs = 60_000L, epochHours = 24)
        assertTrue(s.isAirable(60_000L))
        assertTrue(s.isAirable(s.epochMs))
        assertFalse(s.isAirable(59_999L))
        assertFalse(s.isAirable(s.epochMs + 1))
    }

    @Test
    fun utcMinusFiveAtSixInTheMorning() {
        assertEquals(-1L, Epochs.epochNumber(eastern, 0L))
        assertEquals(-46_800_000L, Epochs.epochStart(eastern, -1L)) // 1969-12-31T11:00Z
        assertEquals(39_600_000L, Epochs.epochStart(eastern, 0L)) // 1970-01-01T11:00Z
        assertEquals(0L, Epochs.epochNumber(eastern, 39_600_000L))
        assertEquals(-1L, Epochs.epochNumber(eastern, 39_599_999L))
        assertEquals(0L, Epochs.epochNumber(eastern, 39_600_000L + MS_DAY - 1))
        assertEquals(1L, Epochs.epochNumber(eastern, 39_600_000L + MS_DAY))
    }

    @Test
    fun utcMinusFiveAtAModernTime() {
        // 2023-11-14T22:13:20Z is 17:13:20 at UTC-5, 11h13m20s into the day that began 06:00 local = 11:00Z that day.
        val t = 1_700_000_000_000L
        assertEquals(19_675L, Epochs.epochNumber(eastern, t))
        assertEquals(1_699_959_600_000L, Epochs.epochStart(eastern, 19_675L)) // 2023-11-14T11:00:00Z
        assertEquals(40_400_000L, t - Epochs.epochStart(eastern, 19_675L)) // 11h13m20s
    }

    @Test
    fun nepalAtHalfPastSixInTheEvening() {
        assertEquals(-1L, Epochs.epochNumber(nepal, 0L))
        assertEquals(-40_500_000L, Epochs.epochStart(nepal, -1L)) // 1969-12-31T12:45Z
        assertEquals(45_900_000L, Epochs.epochStart(nepal, 0L)) // 1970-01-01T12:45Z
        assertEquals(0L, Epochs.epochNumber(nepal, 45_900_000L))
        assertEquals(-1L, Epochs.epochNumber(nepal, 45_899_999L))
    }

    @Test
    fun timesBeforeNineteenSeventyRoundTowardNegativeInfinity() {
        val utc = testSettings(startMinuteOfDay = 0)
        assertEquals(-1L, Epochs.epochNumber(utc, -1L))
        assertEquals(-1L, Epochs.epochNumber(utc, -MS_DAY))
        assertEquals(-2L, Epochs.epochNumber(utc, -MS_DAY - 1))
        assertEquals(-1000L, Epochs.epochNumber(utc, -1000 * MS_DAY))
        assertEquals(-1000L * MS_DAY, Epochs.epochStart(utc, -1000L))
        // 1900-01-01T00:00Z is 25,567 days before the Unix epoch.
        assertEquals(-25_567L, Epochs.epochNumber(utc, -25_567 * MS_DAY))
        assertEquals(-25_568L, Epochs.epochNumber(utc, -25_567 * MS_DAY - 1))
    }

    @Test
    fun everyAllowedEpochLengthStartsAtMidnightUtcOnTheBoundary() {
        for (hours in listOf(1, 2, 3, 4, 6, 8, 12, 24)) {
            val s = testSettings(epochHours = hours, startMinuteOfDay = 0)
            val epochMs = hours * MS_HOUR
            assertEquals(0L, Epochs.epochStart(s, 0L), "$hours h")
            assertEquals(epochMs, Epochs.epochStart(s, 1L), "$hours h")
            assertEquals(-epochMs, Epochs.epochStart(s, -1L), "$hours h")
            assertEquals(0L, Epochs.epochNumber(s, 0L), "$hours h")
            assertEquals(0L, Epochs.epochNumber(s, epochMs - 1), "$hours h")
            assertEquals(1L, Epochs.epochNumber(s, epochMs), "$hours h")
            assertEquals(-1L, Epochs.epochNumber(s, -1L), "$hours h")
            assertEquals(-1L, Epochs.epochNumber(s, -epochMs), "$hours h")
            assertEquals(-2L, Epochs.epochNumber(s, -epochMs - 1), "$hours h")
        }
    }

    @Test
    fun theExtremeOffsetsAndStarts() {
        // +14:00 (840 min), start 00:00 -> the epoch with n = 0 started at 1969-12-31T10:00Z.
        val far = testSettings(utcOffsetMinutes = 840, startMinuteOfDay = 0)
        assertEquals(-50_400_000L, Epochs.epochStart(far, 0L))
        assertEquals(0L, Epochs.epochNumber(far, -50_400_000L))
        assertEquals(-1L, Epochs.epochNumber(far, -50_400_001L))
        // -12:00 (-720 min), start 23:30 (1410): 23:30 local = 11:30Z next day.
        val west = testSettings(utcOffsetMinutes = -720, startMinuteOfDay = 1410)
        assertEquals(127_800_000L, Epochs.epochStart(west, 0L))
        assertEquals(0L, Epochs.epochNumber(west, 127_800_000L))
        assertEquals(-1L, Epochs.epochNumber(west, 127_799_999L))
    }

    @Test
    fun aTimeExactlyOnABoundaryIsTheStartOfTheNextEpoch() {
        for (s in listOf(eastern, nepal, testSettings(epochHours = 6, utcOffsetMinutes = 330, gridMinutes = 30))) {
            for (n in listOf(-3L, -1L, 0L, 1L, 20_000L)) {
                val start = Epochs.epochStart(s, n)
                assertEquals(n, Epochs.epochNumber(s, start))
                assertEquals(n - 1, Epochs.epochNumber(s, start - 1))
                assertEquals(n, Epochs.epochNumber(s, start + s.epochMs - 1))
            }
        }
    }

    @Test
    fun nAndEpochStartAgreeForEveryConfiguration() {
        val times = listOf(-3 * MS_DAY - 7, -1L, 0L, 1L, 1_700_000_000_000L, 4_102_444_800_000L)
        for (hours in listOf(1, 2, 3, 4, 6, 8, 12, 24)) {
            for (offset in listOf(-720, -300, 0, 330, 345, 840)) {
                for (start in listOf(0, 330, 360, 1110, 1410).filter { it % 30 == 0 }) {
                    val s = testSettings(epochHours = hours, utcOffsetMinutes = offset, startMinuteOfDay = start)
                    for (t in times) {
                        val n = Epochs.epochNumber(s, t)
                        val begin = Epochs.epochStart(s, n)
                        val where = "t=$t in epoch $n of $hours h at $offset/$start"
                        assertTrue(begin <= t && t < begin + s.epochMs, where)
                    }
                }
            }
        }
    }

    @Test
    fun theIndependentReferenceAgrees() {
        // epoch-math.json: cases computed by an independent Python reference from the format text.
        val rows = VectorJson.parse(VectorFiles.epochMath).array("vectors").objects()
        assertTrue(rows.size >= 100, "the file has ${rows.size} cases")
        for (row in rows) {
            val input = row.obj("input")
            val (hours, minutes) = input.string("start").split(":").map { it.toInt() }
            val s =
                testSettings(
                    utcOffsetMinutes = input.getValue("utcOffsetMinutes").jsonPrimitive.int,
                    startMinuteOfDay = hours * 60 + minutes,
                    epochHours = input.getValue("epochHours").jsonPrimitive.int,
                )
            val t = input.string("t").toLong()
            val out = row.obj("output")
            val name = row.string("name")
            assertEquals(out.string("n").toLong(), Epochs.epochNumber(s, t), "n: $name")
            val begin = Epochs.epochStart(s, Epochs.epochNumber(s, t))
            assertEquals(out.string("epochStart").toLong(), begin, "epochStart: $name")
        }
    }

    @Test
    fun theGridMarkIsOnTheChannelsOwnClock() {
        // Nepal is UTC+5:45, so a local :00 or :30 is a quarter to or a quarter past the hour in UTC.
        val s = testSettings(utcOffsetMinutes = 345, startMinuteOfDay = 1110, gridMinutes = 30)
        assertTrue(Epochs.isGridMark(s, 45_900_000L)) // 12:45Z = 18:30 Nepal
        assertTrue(Epochs.isGridMark(s, 47_700_000L)) // 13:15Z = 19:00 Nepal
        assertFalse(Epochs.isGridMark(s, 45_000_000L)) // 12:30Z = 18:15 Nepal
        assertFalse(Epochs.isGridMark(s, 45_900_001L))
        assertTrue(Epochs.isGridMark(s, -40_500_000L)) // before 1970: floorMod, not remainder
        assertFalse(Epochs.isGridMark(s, -40_500_001L))
    }

    @Test
    fun theGridMarkHonoursEachGridSize() {
        // India UTC+5:30 on a 60-minute grid: marks at :30Z. On a 15-minute grid: every quarter hour.
        val hourly = testSettings(utcOffsetMinutes = 330, startMinuteOfDay = 360, gridMinutes = 60)
        assertTrue(Epochs.isGridMark(hourly, 30 * MS_MINUTE))
        assertFalse(Epochs.isGridMark(hourly, 0L))
        assertFalse(Epochs.isGridMark(hourly, 90 * MS_MINUTE - 1))
        val quarter = testSettings(utcOffsetMinutes = 345, startMinuteOfDay = 360, gridMinutes = 15)
        assertTrue(Epochs.isGridMark(quarter, 15 * MS_MINUTE))
        assertTrue(Epochs.isGridMark(quarter, 0L))
        assertFalse(Epochs.isGridMark(quarter, 14 * MS_MINUTE))
    }

    @Test
    fun everyEpochStartIsAGridMark() {
        for (grid in listOf(15, 30, 60)) {
            for (offset in listOf(-720, -300, 330, 345, 840)) {
                for (hours in listOf(1, 2, 3, 4, 6, 8, 12, 24)) {
                    val s =
                        testSettings(
                            gridMinutes = grid,
                            utcOffsetMinutes = offset,
                            epochHours = hours,
                            startMinuteOfDay = 360,
                        )
                    for (n in listOf(-1000L, -1L, 0L, 1L, 9999L)) {
                        val where = "grid $grid offset $offset epoch $hours h n $n"
                        assertTrue(Epochs.isGridMark(s, Epochs.epochStart(s, n)), where)
                    }
                }
            }
        }
    }
}
