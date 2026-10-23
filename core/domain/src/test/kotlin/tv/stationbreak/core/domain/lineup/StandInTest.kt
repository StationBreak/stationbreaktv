// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.model.schedule.SchedulerSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StandInTest {
    private val settings =
        SchedulerSettings(OrderingMode.Shuffle, Seed(42uL), 1, true, 30, 24, 0, 360, 6, 60_000L)
    private val hour = 3_600_000L
    private val pool = (1..12).map { item(it, runtimeMs = hour) }
    private val blockStart = 1_760_000_000_000L

    private fun item(
        n: Int,
        runtimeMs: Long,
    ) = ScheduleItem(id(n), ScheduleItemKind.Movie, runtimeMs, ADDED_MS, null, null, 2000, null, null, null)

    private fun pick(
        from: List<ScheduleItem> = pool,
        start: Long = blockStart,
        length: Long = 3 * hour,
        exclude: Set<CanonicalId> = emptySet(),
        n: Long = 20_000L,
    ) = StandIn.pick(settings, n, from, start, length, exclude).map { it.id }

    @Test
    fun pickIsTheSameForTheSameInputs() {
        assertEquals(pick(), pick())
        assertEquals(pick(from = pool.reversed()), pick(), "the order of the pool does not matter")
    }

    @Test
    fun pickDiffersForAnotherBlockStart() {
        val starts = (0 until 5).map { pick(start = blockStart + it * 1_800_000L) }
        assertTrue(starts.toSet().size > 1)
        assertNotEquals(pick(), pick(n = 20_001L))
    }

    @Test
    fun pickNeverReturnsAnExcludedId() {
        val first = pick(length = 12 * hour)
        val banned = first.take(4).toSet()
        val again = pick(length = 12 * hour, exclude = banned)
        assertTrue(again.none { it in banned })
        assertEquals(8, again.size)
    }

    @Test
    fun pickChainsUntilTheLengthIsCovered() {
        val picked = StandIn.pick(settings, 1L, pool, blockStart, 2 * hour + 1, emptySet())
        assertEquals(3, picked.size, "two hours and a millisecond needs a third one-hour item")
        assertEquals(2, StandIn.pick(settings, 1L, pool, blockStart, 2 * hour, emptySet()).size)
    }

    @Test
    fun pickSkipsUnairableRuntimes() {
        val mixed = listOf(item(1, 59_999L), item(2, 25 * hour), item(3, hour))
        assertEquals(listOf(id(3)), pick(from = mixed, length = hour))
    }

    @Test
    fun pickOfAPoolHoldingOnlyTheExcludedItemIsEmpty() {
        assertEquals(emptyList(), pick(from = listOf(item(1, hour)), exclude = setOf(id(1))))
        assertEquals(emptyList(), pick(from = emptyList()))
    }

    @Test
    fun pickReturnsWhatThePoolHasWhenItRunsOut() {
        assertEquals(12, pick(length = 100 * hour).size)
    }

    @Test
    fun pickGoldenValue() {
        val five = (1..5).map { item(it, hour) }
        val order = StandIn.pick(settings, 20_000L, five, blockStart, 5 * hour, emptySet()).map { it.id }
        assertEquals(GOLDEN.map { id(it) }, order)
    }

    private companion object {
        // Derived once by running the pinned formula (seed 42, n 20000, start 1_760_000_000_000) over ids 1..5.
        val GOLDEN: List<Int> = listOf(5, 2, 3, 1, 4)
    }
}
