// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.SchedulerSettings
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DayTailTest {
    private val settings: SchedulerSettings = testSettings(orderingMode = OrderingMode.Chronological)
    private val pool = listOf(movie('a', 30), movie('b', 30), movie('c', 60), movie('d', 30))
    private val n = 20_000L
    private val dayStart = Epochs.epochStart(settings, n)
    private val dayEnd = dayStart + settings.epochMs

    private fun tail(
        from: Long,
        aired: Set<CanonicalId> = emptySet(),
        items: List<tv.stationbreak.core.model.schedule.ScheduleItem> = pool,
        s: SchedulerSettings = settings,
    ) = layoutTail(s, items, n, noFilms(), noWeights(), from, aired)

    private fun ids(blocks: List<ScheduleBlock>): List<CanonicalId> =
        blocks.map { it.content }.filterIsInstance<ScheduleContent.Program>().map { it.itemId }

    @Test
    fun tail_coversFromToDayEnd_contiguous() {
        for (from in listOf(dayStart, dayStart + 7 * MS_HOUR + 10 * MS_MINUTE, dayEnd - 40 * MS_MINUTE)) {
            val blocks = tail(from)
            assertEquals(from, blocks.first().startMs)
            assertEquals(dayEnd, blocks.last().endMs)
            blocks.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
        }
        assertEquals(emptyList(), tail(dayEnd))
    }

    @Test
    fun tail_skipsAiredRuns_unlessCycleWouldEmpty() {
        val from = dayStart + 3 * MS_HOUR
        val aired = setOf(idOf('a'), idOf('b'))
        val blocks = tail(from, aired)
        assertTrue(ids(blocks).none { it in aired })
        assertTrue(ids(blocks).isNotEmpty())
        val all = pool.map { it.id }.toSet()
        val full = tail(from, all)
        assertEquals(tail(from), full)
        assertEquals(dayEnd, full.last().endMs)
    }

    @Test
    fun tail_isDeterministic_anyPoolOrder() {
        val from = dayStart + 5 * MS_HOUR
        for (mode in OrderingMode.entries.filter { it == OrderingMode.Shuffle || it == OrderingMode.Chronological }) {
            val s = testSettings(orderingMode = mode)
            assertEquals(tail(from, items = pool, s = s), tail(from, items = pool.reversed(), s = s))
            val mixed = pool.shuffled(Random(3))
            assertEquals(tail(from, items = pool, s = s), tail(from, items = mixed, s = s))
        }
    }

    @Test
    fun tail_fromDayStart_noneAired_equalsBuildDay() {
        for (mode in listOf(OrderingMode.Shuffle, OrderingMode.Chronological)) {
            val s = testSettings(orderingMode = mode)
            val built = buildDay(s, pool, n, noFilms(), noWeights())
            assertEquals(built.blocks, tail(dayStart, items = built.pool, s = s))
        }
    }

    @Test
    fun tail_emptyPool_isOneSlate() {
        val from = dayStart + MS_HOUR
        assertEquals(listOf(ScheduleBlock(from, dayEnd, ScheduleContent.Slate)), tail(from, items = emptyList()))
    }

    @Test
    fun composedDay_rejectsAGapOrOverlap() {
        val day = buildDay(settings, pool, n, noFilms(), noWeights())
        val b = day.blocks
        val gap = listOf(b[0].copy(endMs = b[0].endMs - 1)) + b.drop(1)
        assertFailsWith<IllegalArgumentException> { composedDay(n, day.startMs, day.endMs, gap, day.pool) }
        val overlap = listOf(b[0].copy(endMs = b[0].endMs + 1)) + b.drop(1)
        assertFailsWith<IllegalArgumentException> { composedDay(n, day.startMs, day.endMs, overlap, day.pool) }
        assertFailsWith<IllegalArgumentException> { composedDay(n, day.startMs, day.endMs, b.dropLast(1), day.pool) }
        assertFailsWith<IllegalArgumentException> { composedDay(n, day.startMs, day.endMs, emptyList(), day.pool) }
    }

    @Test
    fun composedDay_whatsOnMatchesBlocks() {
        val built = buildDay(settings, pool, n, noFilms(), noWeights())
        val day = composedDay(n, built.startMs, built.endMs, built.blocks, built.pool)
        assertEquals(n, day.n)
        assertEquals(built.pool, day.pool)
        for (block in day.blocks) {
            for (t in listOf(block.startMs, block.endMs - 1)) {
                val on = day.whatsOn(t, 2)
                assertEquals(block, on.block)
                assertEquals(built.whatsOn(t, 2), on)
            }
        }
    }
}
