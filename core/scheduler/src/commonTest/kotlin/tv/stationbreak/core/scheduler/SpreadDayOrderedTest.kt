// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.LeftoverPolicy
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The spread end of the day (rules format version 5) for the ordered modes and on the grid, worked by hand in whole
 * minutes, and whole days built with `buildDay` and `layoutTail`.
 */
class SpreadDayOrderedTest {
    private val cases = SpreadCases()
    private val chronological = spreadSettings(OrderingMode.Chronological)
    private val chronologicalGrid = spreadSettings(OrderingMode.Chronological, grid = true)
    private val n = 20_000L

    /** A regular layout of one run of [item] from minute 0. */
    private fun alone(item: ScheduleItem): Laid = cases.laid(0, listOf(listOf(item)))

    @Test
    fun anOrderedChannelUsesGroupedBestFitInOrderAtTheEnd() {
        val films = (1..10).map { cases.single("f$it", 140) }
        val x1 = cases.ep("x1", 1, 1, 15)
        val x2 = cases.ep("x2", 1, 2, 30)
        val y1 = cases.ep("y1", 2, 1, 20)
        // The grouped fit takes x1 and y1 (35 of 40). The 5 minutes left are five 1-minute cards, the last one before
        // the fill, so the day ends on a programme.
        val blocks = cases.spreadOf(chronological, cases.laid(0, films.map { listOf(it) }), listOf(x1, x2, y1), 0, 1440)
        val expected =
            listOf(
                "0-140:f1",
                "140-280:f2",
                "280-281:break",
                "281-421:f3",
                "421-561:f4",
                "561-562:break",
                "562-702:f5",
                "702-842:f6",
                "842-843:break",
                "843-983:f7",
                "983-1123:f8",
                "1123-1124:break",
                "1124-1264:f9",
                "1264-1404:f10",
                "1404-1405:break",
                "1405-1420:x1",
                "1420-1440:y1",
            )
        assertEquals(expected, cases.describe(blocks))
    }

    @Test
    fun anOrderedChannelNeverFillsLessThanFirstFit() {
        // The grouped fit sees only the first 64 shorts (30 of 40 minutes); first fit reaches z beyond them.
        val s = (0 until 64).map { cases.single("s$it", 15) }
        val z = cases.single("z", 10)
        val capped = cases.spreadOf(chronological, alone(cases.single("f", 1400)), s + z, 0, 1440)
        assertEquals(listOf("0-1400:f", "1400-1415:s0", "1415-1430:s1", "1430-1440:z"), cases.describe(capped))
        // A tie keeps the grouped fit: {12, 18} over first fit's {10, 12, 8}.
        val w = listOf(cases.single("w10", 10), cases.single("w12", 12), cases.single("w18", 18), cases.single("w8", 8))
        val tie = cases.spreadOf(chronological, alone(cases.single("g", 1410)), w, 0, 1440)
        assertEquals(listOf("0-1410:g", "1410-1422:w12", "1422-1440:w18"), cases.describe(tie))
        // On the grid: the capped best fit takes one 70 (slot 90) and the extra pass t1, ending at 1420; first fit
        // takes s1, t1 and t2, ending at 1425, and is kept.
        val first = cases.single("g40", 40)
        val long = (2..64).map { cases.single("g70-$it", 70) }
        val t1 = cases.single("t1", 10)
        val t2 = cases.single("t2", 15)
        val visit = listOf(first) + long + t1 + t2
        val grid = cases.spreadOf(chronologicalGrid, alone(cases.single("h", 1320)), visit, 0, 1440)
        val expected =
            listOf(
                "0-1320:h",
                "1320-1360:g40",
                "1360-1380:slate",
                "1380-1390:t1",
                "1390-1410:slate",
                "1410-1425:t2",
                "1425-1440:break",
            )
        assertEquals(expected, cases.describe(grid))
    }

    @Test
    fun aGridExtraPassNeverSkipsAnEarlierEpisode() {
        val x1 = cases.ep("x1", 1, 1, 35)
        val singles = (1..63).map { cases.single("s$it", 40) }
        val z1 = cases.ep("z1", 2, 1, 40)
        val z2 = cases.ep("z2", 2, 2, 10)
        // Best fit is capped at x1 and the 63 singles: it takes x1 (slot 60). 30 minutes are left after the next mark:
        // z2 would fit, but z1 comes first in its group and does not, so nothing is added.
        val f = cases.single("f", 1350)
        val skipped = cases.spreadOf(chronologicalGrid, alone(f), listOf(x1) + singles + z1 + z2, 0, 1440)
        assertEquals(listOf("0-1350:f", "1350-1385:x1", "1385-1440:break"), cases.describe(skipped))
        // x2, right after x1 (the group's chosen prefix), is added.
        val x2 = cases.ep("x2", 1, 2, 20)
        val added = cases.spreadOf(chronologicalGrid, alone(f), listOf(x1) + singles + x2, 0, 1440)
        assertEquals(
            listOf("0-1350:f", "1350-1385:x1", "1385-1410:slate", "1410-1430:x2", "1430-1440:break"),
            cases.describe(added),
        )
    }

    @Test
    fun anOrderedGridChannelKeepsItsEndOnTheGrid() {
        val items = gridItems()
        for (mode in listOf(OrderingMode.Chronological, OrderingMode.InOrder)) {
            for (seed in 1uL..6uL) {
                val spread = testSettings(orderingMode = mode, seed = Seed(seed), leftover = LeftoverPolicy.Spread)
                val day = buildDay(spread, items, n, noFilms(), noWeights())
                assertTiles(day.blocks, day.startMs, day.endMs, grid = true)
                for (block in day.blocks) {
                    if (block.content is ScheduleContent.Program) {
                        assertEquals(0L, (block.startMs + spread.offsetMs).mod(spread.gridMs), "off the grid: $block")
                    }
                }
                val breaks = day.blocks.indices.filter { day.blocks[it].content == ScheduleContent.Break }
                assertTrue(breaks.all { it == day.blocks.lastIndex }, "a break before the closing one")
                assertTrue(day.blocks.last().content != ScheduleContent.Slate, "a slate at the end")
                val gather = buildDay(spread.copy(leftover = LeftoverPolicy.Gather), items, n, noFilms(), noWeights())
                assertTrue(lastProgrammeEnd(day.blocks) >= lastProgrammeEnd(gather.blocks), "fills less than today")
            }
        }
    }

    @Test
    fun gatherIsUnchanged() {
        val items = gridItems()
        for (mode in SCHEDULED_MODES) {
            for (grid in listOf(true, false)) {
                for (seed in 1uL..5uL) {
                    val s =
                        testSettings(
                            orderingMode = mode,
                            seed = Seed(seed),
                            alignToGrid = grid,
                            blockLength = 2,
                            leftover = LeftoverPolicy.Gather,
                        )
                    val day = buildDay(s, items, n, noFilms(), noWeights())
                    assertEquals(DayOracle.blocks(s, items, n), day.blocks, "$mode grid=$grid seed=$seed")
                    assertTrue(day.blocks.none { it.content == ScheduleContent.Break })
                    // Gather never reads what aired before the tail.
                    val from = day.startMs + 9 * MS_HOUR
                    val before =
                        day.blocks
                            .filter { it.endMs <= from && it.content is ScheduleContent.Program }
                            .map { PastAiring(itemOf(items, it), it.endMs) }
                    val ids = before.mapTo(HashSet()) { it.item.id }
                    val tail = layoutTail(s, day.pool, n, noFilms(), noWeights(), from, ids, before)
                    val plain = layoutTail(s, day.pool, n, noFilms(), noWeights(), from, ids, emptyList())
                    assertEquals(plain, tail)
                }
            }
        }
    }

    @Test
    fun anAllMovieDayHasNoAdjacentBreaksAndMinuteCards() {
        val films = (0 until 15).map { cases.single("m$it", 85 + (it * 7) % 50) }
        var withBreaks = 0
        for (mode in listOf(OrderingMode.Shuffle, OrderingMode.Curated)) {
            for (seed in 1uL..10uL) {
                val s =
                    testSettings(
                        orderingMode = mode,
                        seed = Seed(seed),
                        alignToGrid = false,
                        leftover = LeftoverPolicy.Spread,
                    )
                val day = buildDay(s, films, n, noFilms(), noWeights())
                assertTiles(day.blocks, day.startMs, day.endMs, grid = false)
                val breaks = day.blocks.indices.filter { day.blocks[it].content == ScheduleContent.Break }
                if (breaks.isNotEmpty()) withBreaks++
                assertTrue(breaks.size <= 9, "at most one break per 150 minutes")
                for ((a, b) in breaks.zipWithNext()) {
                    val between = day.blocks.subList(a + 1, b).count { it.content is ScheduleContent.Program }
                    assertTrue(between >= 2, "breaks at least two run boundaries apart")
                }
                val total = breaks.sumOf { day.blocks[it].lengthMs }
                if (total >= MIN_CARD_MS) {
                    assertTrue(breaks.all { day.blocks[it].lengthMs >= MIN_CARD_MS }, "a card under 60 s")
                }
                // Two breaks are never at neighbouring boundaries, so at most every other film is followed by one.
                val programmes = day.blocks.count { it.content is ScheduleContent.Program }
                assertTrue(breaks.size * 2 <= programmes + 1, "a card after every film")
            }
        }
        assertTrue(withBreaks > 0)
    }

    @Test
    fun aOneHourSpreadDayHasAtMostOneBreakAtItsEnd() {
        val shorts = (0 until 9).map { cases.single("o$it", 7 + 3 * it) }
        for (seed in 1uL..10uL) {
            val s =
                testSettings(
                    orderingMode = OrderingMode.Shuffle,
                    seed = Seed(seed),
                    alignToGrid = false,
                    epochHours = 1,
                    leftover = LeftoverPolicy.Spread,
                )
            val day = buildDay(s, shorts, n, noFilms(), noWeights())
            assertTiles(day.blocks, day.startMs, day.endMs, grid = false)
            val breaks = day.blocks.indices.filter { day.blocks[it].content == ScheduleContent.Break }
            assertTrue(breaks.all { it == day.blocks.lastIndex }, "one break, at c, closing the day")
        }
    }

    @Test
    fun aSpreadTailFromTheDayStartIsTheDayAndALaterOneTilesTheRest() {
        val items = gridItems()
        for (mode in listOf(OrderingMode.Shuffle, OrderingMode.Chronological, OrderingMode.Curated)) {
            val s = testSettings(orderingMode = mode, alignToGrid = false, leftover = LeftoverPolicy.Spread)
            val built = buildDay(s, items, n, noFilms(), noWeights())
            val whole = layoutTail(s, built.pool, n, noFilms(), noWeights(), built.startMs, emptySet(), emptyList())
            assertEquals(built.blocks, whole, "$mode")
            val from = built.startMs + 9 * MS_HOUR
            val before = built.blocks.filter { it.startMs < from && it.content is ScheduleContent.Program }
            val aired = before.mapTo(HashSet()) { (it.content as ScheduleContent.Program).itemId }
            val pastAirings = before.map { PastAiring(itemOf(items, it), minOf(it.endMs, from)) }
            val tail = layoutTail(s, built.pool, n, noFilms(), noWeights(), from, aired, pastAirings)
            assertTiles(tail, from, built.endMs, grid = false)
        }
    }

    private fun gridItems(): List<ScheduleItem> =
        listOf(
            movie('1', 95),
            movie('2', 47),
            episode('3', 'a', 1, 1, 22),
            episode('4', 'a', 1, 2, 23),
            episode('5', 'a', 1, 3, 44),
            movie('6', 130),
            movie('7', 61),
            episode('8', 'b', 1, 1, 30),
            episode('9', 'b', 1, 2, 31),
            episode('c', 'b', 1, 3, 26),
        )

    private fun itemOf(
        items: List<ScheduleItem>,
        block: ScheduleBlock,
    ): ScheduleItem = items.first { it.id == (block.content as ScheduleContent.Program).itemId }

    private fun lastProgrammeEnd(blocks: List<ScheduleBlock>): Long {
        val last = blocks.last { it.content is ScheduleContent.Program }
        return last.endMs
    }
}
