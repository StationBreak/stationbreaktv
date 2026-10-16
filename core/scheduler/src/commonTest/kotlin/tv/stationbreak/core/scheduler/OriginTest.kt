// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where a day starts in its order (spec 08, Unit 3): `shuffle` at run 0; `chronological` and `randomInOrder` at the run
 * of the continuous playout that contains `S`, found with the origin `-offsetMs` and slot lengths. Blocks that depend
 * on
 * the end-of-day fill are left out of the expected lists (`EndFillTest` covers the fill).
 */
class OriginTest {
    @Suppress("LongParameterList")
    private fun settings(
        mode: OrderingMode = OrderingMode.Chronological,
        alignToGrid: Boolean = true,
        gridMinutes: Int = 30,
        epochHours: Int = 1,
        startMinuteOfDay: Int = 30,
        utcOffsetMinutes: Int = 0,
        blockLength: Int = 1,
    ) = testSettings(
        orderingMode = mode,
        blockLength = blockLength,
        alignToGrid = alignToGrid,
        gridMinutes = gridMinutes,
        epochHours = epochHours,
        utcOffsetMinutes = utcOffsetMinutes,
        startMinuteOfDay = startMinuteOfDay,
    )

    private val threePrograms = listOf(movie('1', 20), movie('2', 40), movie('3', 30))

    // --- The largest j with Q[j] <= pos, with slot lengths when aligned ---------------------------------------------

    @Test
    fun alignedSlotsGiveTheRunThatContainsS() {
        // Slots 30, 60, 30: Q = [0, 30, 90, 120]. Days start at minute 30 + 60n.
        val s = settings()
        val day0 = buildDay(s, threePrograms, 0L) // S = 30, pos = 30, j = 1 (Q[1] = 30 <= 30)
        assertEquals(1, day0.startRunIndex)
        assertEquals(listOf("30-70:2", "70-90:slate"), day0.describe())
        val day1 = buildDay(s, threePrograms, 1L) // S = 90, pos = 90, j = 2 (Q[2] = 90 <= 90): an exact boundary
        assertEquals(2, day1.startRunIndex)
        assertEquals(listOf("90-120:3", "120-140:1", "140-150:slate"), day1.describe().take(3))
        val day2 = buildDay(s, threePrograms, 2L) // S = 150, pos = 30, j = 1 again
        assertEquals(1, day2.startRunIndex)
        assertEquals("150-190:2", day2.describe().first())
    }

    @Test
    fun theRunAfterTheLastIsRunZero() {
        // Day 1 above starts at run 2 (the last) and its next block is run 0.
        val day = buildDay(settings(), threePrograms, 1L)
        assertEquals(listOf("90-120:3", "120-140:1"), day.describe().take(2))
    }

    @Test
    fun aDayBeforeTheEpochUsesFloorModulo() {
        // n = -1: S = -30, pos = floorMod(-30, 120) = 90, j = 2. Truncating division would give pos = -30.
        val day = buildDay(settings(), threePrograms, -1L)
        assertEquals(2, day.startRunIndex)
        assertEquals(listOf("-30-0:3", "0-20:1", "20-30:slate"), day.describe())
    }

    @Test
    fun withoutTheGridTheSlotIsTheRuntime() {
        // Runtimes 20, 40, 30: Q = [0, 20, 60, 90]. S = 30 gives j = 1; S = 90 gives pos = 0, j = 0.
        val s = settings(alignToGrid = false)
        val day0 = buildDay(s, threePrograms, 0L)
        assertEquals(1, day0.startRunIndex)
        assertEquals("30-70:2", day0.describe().first())
        val day1 = buildDay(s, threePrograms, 1L)
        assertEquals(0, day1.startRunIndex)
        assertEquals(listOf("90-110:1", "110-150:2"), day1.describe())
    }

    @Test
    fun runJStartsExactlyAtSWhateverTheLag() {
        // A 20-minute item in a 30-minute slot: the continuous playout is 5..29 minutes into slot 0 at S, but run 0
        // still starts at S exactly.
        val s = settings(epochHours = 1, startMinuteOfDay = 0)
        val items = listOf(movie('1', 20), movie('2', 50))
        for (n in -4L..4L) {
            val day = buildDay(s, items, n)
            assertEquals(Epochs.epochStart(s, n), day.blocks.first().startMs)
            assertTrue(day.blocks.first().content is ScheduleContent.Program)
        }
    }

    @Test
    fun runsLeftOutOfTheCycleDoNotCountInThePrefixSums() {
        // blockLength 2 over items 1..6: runs [1 2] = 40, [3 4] = 190 (longer than the 180-minute epoch: left out),
        // [5 6] = 80. Without the left-out run Q = [0, 40, 120]; with it, [0, 40, 230, 310].
        val items =
            listOf(movie('1', 20), movie('2', 20), movie('3', 100), movie('4', 90), movie('5', 40), movie('6', 40))
        val s = settings(alignToGrid = false, epochHours = 3, blockLength = 2)
        val day1 = buildDay(s, items, 1L) // S = 210, pos = 90, j = 1: run [5 6]
        assertEquals(2, day1.cycleRunCount)
        assertEquals(listOf("210-250:5", "250-290:6", "290-310:1"), day1.describe().take(3))
        val day2 = buildDay(s, items, 2L) // S = 390, pos = 30, j = 0: run [1 2]
        assertEquals("390-410:1", day2.describe().first())
    }

    @Test
    fun shuffleAlwaysStartsAtRunZeroOfTheDaysOwnOrder() {
        val s = settings(mode = OrderingMode.Shuffle, alignToGrid = false)
        val items = listOf(movie('1', 20), movie('2', 40), movie('3', 30), movie('4', 10))
        for (n in -2L..3L) {
            val expectedFirst = Orderings.shuffle(EpochPool.build(s, items, n), Epochs.seedN(s.seed, n)).first()
            val day = buildDay(s, items, n)
            assertEquals(0, day.startRunIndex)
            assertEquals(ScheduleContent.Program(expectedFirst.id), day.blocks.first().content)
            assertEquals(Epochs.epochStart(s, n), day.blocks.first().startMs)
        }
    }

    @Test
    fun randomInOrderStartsWhereItsContinuousPlayoutIs() {
        val items =
            listOf(
                episode('a', 'e', 1, 1, 20),
                episode('b', 'e', 1, 2, 40),
                episode('c', 'f', 1, 1, 30),
                movie('d', 50),
            )
        val s = settings(mode = OrderingMode.RandomInOrder, alignToGrid = true, epochHours = 2, startMinuteOfDay = 0)
        for (n in -3L..3L) {
            val cycle = DayOracle.cycle(s, EpochPool.build(s, items, n), Epochs.seedN(s.seed, n))
            val expected = DayOracle.continuousRun(s, cycle, Epochs.epochStart(s, n))
            val day = buildDay(s, items, n)
            assertEquals(expected, day.startRunIndex, "n = $n")
            assertEquals(ScheduleContent.Program(cycle[expected].items.first().id), day.blocks.first().content)
        }
    }

    @Test
    fun aDayThatStartsExactlyOnTheLastRunsBoundaryStartsAtTheLastRun() {
        // Unaligned runtimes 20, 25, 35: Q = [0, 20, 45, 80] minutes, C = 80. Nepal +345 on a 15-minute grid, 1 h
        // epochs from 00:45, so S + offset = 45 + 60n minutes on the channel's clock. For n = -4 that is -195 minutes,
        // a negative time, and floorMod(-195, 80) = 45 = Q[2] with j = B - 1 = 2. (Truncating remainder: -35.)
        val s =
            settings(
                alignToGrid = false,
                gridMinutes = 15,
                epochHours = 1,
                startMinuteOfDay = 45,
                utcOffsetMinutes = 345,
            )
        val items = listOf(movie('1', 20), movie('2', 25), movie('3', 35))
        val day = buildDay(s, items, -4L)
        assertEquals(-195 * MS_MINUTE - 345 * MS_MINUTE, day.startMs)
        assertEquals(2, day.startRunIndex)
        assertEquals(
            listOf("-540--505:3", "-505--485:1", "-485--480:slate"),
            day.describe(),
        )
        // A day at n = 0 has pos 45 too.
        assertEquals(2, buildDay(s, items, 0L).startRunIndex)
    }

    // --- Nepal and India: every regular run starts on a grid mark ----------------------------------------------------

    private val mixed =
        listOf(movie('1', 22), movie('2', 41), movie('3', 55), movie('4', 8), movie('5', 29), movie('6', 63))

    private fun assertEveryRunStartsOnAGridMark(
        s: SchedulerSettings,
        items: List<ScheduleItem>,
    ) {
        for (mode in OrderingMode.entries) {
            val m = s.copy(orderingMode = mode, seed = Seed(77uL))
            for (n in -6L..6L) {
                val day = buildDay(m, items, n)
                val label = "$mode n=$n offset=${m.utcOffsetMinutes}"
                assertTrue(Epochs.isGridMark(m, day.startMs), "S is a grid mark: $label")
                assertTrue(Epochs.isGridMark(m, day.endMs), "the next S is a grid mark: $label")
                for (block in day.blocks) {
                    if (block.content is ScheduleContent.Program) {
                        val onMark = Epochs.isGridMark(m, block.startMs)
                        assertTrue(onMark, "program at ${block.startMs} off the grid: $label")
                    }
                }
            }
        }
    }

    @Test
    fun nepalOnA30MinuteGridStartsEveryRunOnAMark() {
        val nepal = settings(gridMinutes = 30, epochHours = 12, startMinuteOfDay = 18 * 60 + 30, utcOffsetMinutes = 345)
        assertEveryRunStartsOnAGridMark(nepal, mixed)
    }

    @Test
    fun indiaOnA60MinuteGridStartsEveryRunOnAMark() {
        val india = settings(gridMinutes = 60, epochHours = 12, startMinuteOfDay = 6 * 60, utcOffsetMinutes = 330)
        assertEveryRunStartsOnAGridMark(india, mixed)
    }

    @Test
    fun nepalWithRunsOfSeveralItemsStartsEveryRunOnAMark() {
        val nepal =
            settings(
                gridMinutes = 30,
                epochHours = 8,
                startMinuteOfDay = 18 * 60 + 30,
                utcOffsetMinutes = 345,
                blockLength = 3,
            )
        for (mode in OrderingMode.entries) {
            val m = nepal.copy(orderingMode = mode)
            for (n in -4L..4L) {
                val day = buildDay(m, mixed, n)
                // The first program after a slate, and the first block of the day, is the start of a run.
                val starts =
                    day.blocks.filterIndexed { i, b ->
                        b.content is ScheduleContent.Program &&
                            (i == 0 || day.blocks[i - 1].content == ScheduleContent.Slate)
                    }
                assertTrue(starts.all { Epochs.isGridMark(m, it.startMs) }, "$mode n=$n")
            }
        }
    }
}
