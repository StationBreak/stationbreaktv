// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BestFitTest {
    private fun idAt(index: Int): CanonicalId = CanonicalId.parse(index.toString(16).padStart(32, '0'))

    private fun randomItem(
        gen: SeededGen,
        index: Int,
        maxMs: Int,
    ) = testItem(idAt(index), runtimeMs = gen.nextInt(1_000..maxMs).toLong())

    private fun items(vararg minutes: Int): List<ScheduleItem> =
        minutes.mapIndexed { i, m -> testItem(idAt(i + 1), runtimeMs = m * MS_MINUTE) }

    private fun fit(
        candidates: List<ScheduleItem>,
        budgetMinutes: Int,
    ) = bestFit(candidates, budgetMinutes * MS_MINUTE) { it.runtimeMs }.of(candidates)

    /** The candidates at these positions, which the fits return. */
    private fun List<Int>.of(candidates: List<ScheduleItem>): List<ScheduleItem> = map { candidates[it] }

    private fun List<ScheduleItem>.minutes() = map { (it.runtimeMs / MS_MINUTE).toInt() }

    @Test
    fun itFillsExactlyWhenItCan() {
        assertEquals(listOf(11, 4), fit(items(7, 11, 4), 15).minutes())
    }

    @Test
    fun tiesGoToTheEarlierSet() {
        val candidates = items(5, 5, 10)
        assertEquals(candidates.take(2), fit(candidates, 10))
    }

    @Test
    fun nothingFitsGivesEmpty() {
        assertEquals(emptyList(), fit(items(20, 30), 10))
        assertEquals(emptyList(), fit(emptyList(), 10))
        assertEquals(emptyList(), bestFit(items(1), 999L) { it.runtimeMs })
    }

    @Test
    fun onlyTheFirst64CandidatesCount() {
        // 64 one-minute items, then a 5-minute one. With 65 minutes the 65th would give 60 + 5, but it is past the cap.
        assertEquals(List(64) { 1 }, fit(items(*IntArray(64) { 1 }, 5), 65).minutes())
        // Items over the budget are not in the pool, so they do not use up the cap: 64 ones follow ten 100s.
        val mixed = items(*IntArray(10) { 100 }, *IntArray(64) { 1 }, 2)
        assertEquals(64, fit(mixed, 66).size)
        assertEquals(List(64) { 1 }, fit(mixed, 66).minutes())
    }

    @Test
    fun weightsRoundUpAndTheBudgetRoundsDown() {
        val a = testItem(idAt(1), runtimeMs = 1_001L)
        // 1.001 s weighs 2 s; a 1.999 s budget is 1 s.
        assertEquals(emptyList(), bestFit(listOf(a), 1_999L) { it.runtimeMs })
        assertEquals(listOf(0), bestFit(listOf(a), 2_000L) { it.runtimeMs })
    }

    @Test
    fun aWeightUnderOneSecondIsRejected() {
        assertFailsWith<IllegalArgumentException> { bestFit(items(1), MS_MINUTE) { 999L } }
        assertFailsWith<IllegalArgumentException> { bestGroupedFit(items(1), { it.id }, MS_MINUTE) { 0L } }
    }

    @Test
    fun aBudgetOverADayCountsAsADayAndAHugeWeightDoesNotOverflow() {
        // 1440 minutes is the whole day; with two days of budget only one day counts, so the extra minute is left out.
        val c = items(1440, 1)
        assertEquals(c.take(1), fit(c, 2 * 1440))
        assertEquals(listOf(0), bestGroupedFit(c, { it.id }, 2 * MS_DAY) { it.runtimeMs })
        assertEquals(emptyList(), bestFit(items(1), MS_DAY) { Long.MAX_VALUE })
        assertEquals(emptyList(), bestGroupedFit(items(1), { it.id }, MS_DAY) { Long.MAX_VALUE })
    }

    @Test
    fun theRealSumNeverExceedsTheBudget() {
        forAllCases("realSumWithinBudget", 0x5EEDuL, cases = 300) { gen ->
            val candidates = (1..gen.nextInt(1..80)).map { randomItem(gen, it, 3_600_000) }
            val budget = gen.nextInt(0..4_000_000).toLong()
            val chosen = bestFit(candidates, budget) { it.runtimeMs }.of(candidates)
            assertTrue(chosen.sumOf { it.runtimeMs } <= budget, "sum over budget")
            var at = -1
            for (c in chosen) {
                val next = candidates.indexOf(c)
                assertTrue(next > at, "not in candidate order")
                at = next
            }
            assertEquals(chosen, bestFit(candidates, budget) { it.runtimeMs }.of(candidates), "not deterministic")
        }
    }

    private fun grouped(
        candidates: List<ScheduleItem>,
        groups: Map<CanonicalId, CanonicalId>,
        budgetMinutes: Int,
    ): List<ScheduleItem> {
        val chosen = bestGroupedFit(candidates, { groups.getValue(it.id) }, budgetMinutes * MS_MINUTE) { it.runtimeMs }
        return chosen.of(candidates)
    }

    @Test
    fun groupedFitOnlyTakesPrefixes() {
        val c = items(10, 3, 4)
        val a = idOf('a')
        val b = idOf('b')
        val result = grouped(c, mapOf(c[0].id to a, c[1].id to a, c[2].id to b), 7)
        assertEquals(listOf(4), result.minutes())
    }

    @Test
    fun groupedFitChoosesAmongSeveralPrefixesOfOneGroup() {
        val c = items(4, 3, 5, 6)
        val a = idOf('a')
        val b = idOf('b')
        val result = grouped(c, mapOf(c[0].id to a, c[1].id to a, c[2].id to a, c[3].id to b), 12)
        assertEquals(listOf(4, 3, 5), result.minutes())
    }

    @Test
    fun theCapOnlyCutsAGroupsTail() {
        val c = items(*IntArray(100) { 1 + it % 3 })
        val groupIds = listOf(idOf('a'), idOf('b'), idOf('c'))
        val groups = c.indices.associate { c[it].id to groupIds[it % 3] }
        val result = grouped(c, groups, 10_000)
        // The cap keeps the first 64 candidates; each group's kept items are a prefix of that group's candidates.
        assertEquals(c.take(64), result)
        for (g in groupIds) {
            val all = c.filter { groups.getValue(it.id) == g }
            val kept = result.filter { groups.getValue(it.id) == g }
            assertEquals(all.take(kept.size), kept)
        }
    }

    @Test
    fun groupedResultIsAPrefixPerGroupWithinBudget() {
        forAllCases("groupedPrefixes", 0xBEEFuL, cases = 300) { gen ->
            val c = (1..gen.nextInt(1..40)).map { randomItem(gen, it, 1_200_000) }
            val groupIds = (1..4).map { idAt(1000 + it) }
            val groups = c.associate { it.id to gen.pick(groupIds) }
            val budget = gen.nextInt(0..2_000_000).toLong()
            val chosen = bestGroupedFit(c, { groups.getValue(it.id) }, budget) { it.runtimeMs }.of(c)
            assertTrue(chosen.sumOf { it.runtimeMs } <= budget, "sum over budget")
            for (g in groupIds) {
                val all = c.filter { groups.getValue(it.id) == g }
                val kept = chosen.filter { groups.getValue(it.id) == g }
                assertEquals(all.take(kept.size), kept, "not a prefix")
            }
        }
    }
}
