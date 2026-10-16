// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The regular layout of a day (spec 08, Unit 3), worked by hand. Every case uses chronological movies with no dates,
 * which play in ascending ID order, and a day that starts at minute 0 (n = 0, start 00:00, offset 0). The continuous
 * playout then starts at run 0, so these cases do not depend on the day's starting point (that is `OriginTest`).
 */
class TimelineTest {
    private fun day(
        items: List<ScheduleItem>,
        alignToGrid: Boolean,
        gridMinutes: Int = 30,
        epochHours: Int = 3,
        blockLength: Int = 1,
    ) = buildDay(
        testSettings(
            orderingMode = OrderingMode.Chronological,
            blockLength = blockLength,
            alignToGrid = alignToGrid,
            gridMinutes = gridMinutes,
            epochHours = epochHours,
            startMinuteOfDay = 0,
        ),
        items,
        0L,
    )

    @Test
    fun withoutTheGridRunsPlayBackToBackAndTheCycleRepeats() {
        val built = day(listOf(movie('1', 20), movie('2', 25), movie('3', 35)), alignToGrid = false)
        assertEquals(
            listOf("0-20:1", "20-45:2", "45-80:3", "80-100:1", "100-125:2", "125-160:3", "160-180:1"),
            built.describe(),
        )
    }

    @Test
    fun withTheGridEachRunStartsOnTheNextMarkWithASlateBefore() {
        val built = day(listOf(movie('1', 20), movie('2', 25), movie('3', 35)), alignToGrid = true)
        assertEquals(
            listOf(
                "0-20:1",
                "20-30:slate",
                "30-55:2",
                "55-60:slate",
                "60-95:3",
                "95-120:slate",
                "120-140:1",
                "140-150:slate",
                "150-175:2",
                "175-180:slate",
            ),
            built.describe(),
        )
    }

    @Test
    fun aGridOf15RoundsUpToTheNextQuarterHour() {
        val built = day(listOf(movie('1', 20)), alignToGrid = true, gridMinutes = 15, epochHours = 2)
        assertEquals(
            listOf(
                "0-20:1",
                "20-30:slate",
                "30-50:1",
                "50-60:slate",
                "60-80:1",
                "80-90:slate",
                "90-110:1",
                "110-120:slate",
            ),
            built.describe(),
        )
    }

    @Test
    fun aGridOf60RoundsUpToTheNextHour() {
        val built = day(listOf(movie('1', 20)), alignToGrid = true, gridMinutes = 60, epochHours = 2)
        assertEquals(listOf("0-20:1", "20-60:slate", "60-80:1", "80-120:slate"), built.describe())
    }

    @Test
    fun aRunOfSeveralItemsIsSeveralBlocksWithTheGapOnlyAfterTheRun() {
        val items = listOf(movie('1', 20), movie('2', 25), movie('3', 35), movie('4', 10))
        val built = day(items, alignToGrid = true, blockLength = 2)
        assertEquals(
            listOf(
                "0-20:1",
                "20-45:2",
                "45-60:slate",
                "60-95:3",
                "95-105:4",
                "105-120:slate",
                "120-140:1",
                "140-165:2",
                "165-180:slate",
            ),
            built.describe(),
        )
    }

    @Test
    fun noGapMeansNoSlateAndEveryBlockIsHalfOpenAndLongerThanZero() {
        val built = day(listOf(movie('1', 30), movie('2', 30)), alignToGrid = true, epochHours = 2)
        assertEquals(listOf("0-30:1", "30-60:2", "60-90:1", "90-120:2"), built.describe())
        assertTrue(built.blocks.all { it.endMs > it.startMs })
        // Half-open: each block ends exactly where the next begins, so an edge belongs to the later one.
        built.blocks.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
    }

    @Test
    fun aRunWhoseSlotEqualsTheEpochStaysInTheCycle() {
        val built = day(listOf(movie('1', 120)), alignToGrid = true, epochHours = 2)
        assertEquals(listOf("0-120:1"), built.describe())
    }

    @Test
    fun anEmptyPoolIsOneSlateForTheWholeDay() {
        val built = day(emptyList(), alignToGrid = true)
        assertEquals(listOf("0-180:slate"), built.describe())
        assertEquals(0, built.cycleRunCount)
    }

    @Test
    fun aPoolOfItemsThatAreAllTooLongIsAnEmptyPool() {
        val built = day(listOf(movie('1', 121), movie('2', 400)), alignToGrid = false, epochHours = 2)
        assertEquals(listOf("0-120:slate"), built.describe())
    }

    @Test
    fun aRunLongerThanTheEpochIsLeftOutOfTheCycle() {
        // Run [1, 2] is 130 minutes: longer than the 120-minute epoch, so it can never fit. Only run [3] is played.
        val items = listOf(movie('1', 70), movie('2', 60), movie('3', 10))
        val built = day(items, alignToGrid = false, epochHours = 2, blockLength = 2)
        assertEquals(1, built.cycleRunCount)
        assertEquals(
            (0 until 12).map { "${it * 10}-${it * 10 + 10}:3" },
            built.describe(),
        )
    }

    @Test
    fun theSlotNotTheRuntimeDecidesWhetherARunIsLeftOut() {
        // 100 minutes is within a 2 h epoch, but on a 60-minute grid its slot is 120 (fits); 130 would be 180 (left
        // out).
        val fits = day(listOf(movie('1', 100)), alignToGrid = true, gridMinutes = 60, epochHours = 2)
        assertEquals(listOf("0-100:1", "100-120:slate"), fits.describe())
        val pair = listOf(movie('1', 70), movie('2', 60))
        val tooLong = day(pair, alignToGrid = true, gridMinutes = 60, epochHours = 2, blockLength = 2)
        assertEquals(0, tooLong.cycleRunCount)
        assertEquals(listOf("0-120:slate"), tooLong.describe())
    }

    @Test
    fun everyRunLongerThanTheEpochMakesTheDayOneSlate() {
        val items = listOf(movie('1', 70), movie('2', 60))
        val built = day(items, alignToGrid = false, epochHours = 2, blockLength = 2)
        assertEquals(listOf("0-120:slate"), built.describe())
    }

    @Test
    fun aDayOfOneSecondProgramsBuildsEightySixThousandFourHundredBlocks() {
        // minRuntimeSeconds = 1, unaligned, 24 hours: the largest number of blocks a day can have.
        val items =
            (0 until 50).map {
                testItem(CanonicalId.parse(it.toString(16).padStart(32, '0')), runtimeMs = 1_000L, createdAtMs = LONG_AGO)
            }
        val s =
            testSettings(
                orderingMode = OrderingMode.Chronological,
                alignToGrid = false,
                epochHours = 24,
                startMinuteOfDay = 0,
                minRuntimeMs = 1_000L,
            )
        val built = buildDay(s, items, 0L)
        assertEquals(86_400, built.blocks.size)
        assertTrue(built.blocks.all { it.lengthMs == 1_000L })
        assertEquals(0L, built.blocks.first().startMs)
        assertEquals(MS_DAY, built.blocks.last().endMs)
        assertEquals(1_000L, built.whatsOn(MS_DAY - 1, 3).block.lengthMs)
    }

    @Test
    fun aDayCoversExactlyItsEpochForEveryGridAndEpochLength() {
        for (gridMinutes in listOf(15, 30, 60)) {
            for (epochHours in listOf(1, 2, 3, 6, 24)) {
                for (aligned in listOf(false, true)) {
                    val items = listOf(movie('1', 17), movie('2', 41), movie('3', 55), movie('4', 8))
                    val built = day(items, aligned, gridMinutes, epochHours)
                    val label = "grid $gridMinutes, $epochHours h, aligned $aligned"
                    assertEquals(0L, built.blocks.first().startMs, label)
                    assertEquals(epochHours * MS_HOUR, built.blocks.last().endMs, label)
                    built.blocks.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs, label) }
                    assertTrue(built.blocks.none { it.endMs > epochHours * MS_HOUR }, label)
                    // Two slates are never adjacent: they would be one block.
                    built.blocks.zipWithNext().forEach { (a, b) ->
                        assertTrue(a.content != ScheduleContent.Slate || b.content != ScheduleContent.Slate, label)
                    }
                }
            }
        }
    }
}
