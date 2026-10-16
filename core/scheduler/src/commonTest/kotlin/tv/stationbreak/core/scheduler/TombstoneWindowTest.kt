// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.schedule.ScheduleItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Two devices see the same library but first notice a deletion at different moments (spec 08, Unit 1 and 3). If each
 * syncs at least every G, a device's `deletedAt` is its true deletion time `d` plus a delay in `[0, G]`. They can then
 * disagree about whether day n's pool holds the item only when `d` is in `[S - 2G, S - G)`.
 */
class TombstoneWindowTest {
    private fun device(
        items: List<ScheduleItem>,
        deletedAt: Map<Int, Long>,
        delays: Map<Int, Long>,
    ): List<ScheduleItem> =
        items.mapIndexed { i, item ->
            val d = deletedAt[i]
            if (d == null) item else item.copy(deletedAtMs = d + delays.getValue(i))
        }

    @Test
    fun devicesDisagreeOnlyForDeletionsInTheWindow() {
        var windowCases = 0
        var agreeingDays = 0
        forAllCases("tombstone window", 0x70B5uL) { gen ->
            val base = generateCase(gen)
            val s = base.settings.copy(graceHours = gen.nextInt(1..12))
            val g = s.graceMs
            val cutoff = Epochs.epochStart(s, base.n) - g
            // True deletion moments from 3G before the cut-off to G after it, with edges over-represented.
            val deletedAt =
                base.items.indices
                    .filter { gen.chance(60) }
                    .associateWith {
                        when (gen.nextInt(6)) {
                            0 -> cutoff - g

                            // the window's first moment, S - 2G
                            1 -> cutoff - 1

                            // the window's last moment
                            2 -> cutoff

                            // just outside
                            3 -> cutoff - g - 1

                            // just before the window
                            else -> cutoff - 3 * g + gen.nextInt(0..(4 * (g / 1000L).toInt())) * 1000L
                        }
                    }
            val delaysA = deletedAt.mapValues { gen.pick(listOf(0L, g, gen.nextInt(0..(g / 1000L).toInt()) * 1000L)) }
            val delaysB = deletedAt.mapValues { gen.pick(listOf(0L, g, gen.nextInt(0..(g / 1000L).toInt()) * 1000L)) }
            val a = device(base.items, deletedAt, delaysA)
            val b = device(base.items, deletedAt, delaysB)

            val poolA = EpochPool.build(s, a, base.n).map { it.id }.toSet()
            val poolB = EpochPool.build(s, b, base.n).map { it.id }.toSet()
            val disagree = (poolA - poolB) + (poolB - poolA)
            val inWindow =
                deletedAt
                    .filterValues { it >= cutoff - g && it < cutoff }
                    .keys
                    .map { base.items[it].id }
                    .toSet()
            assertTrue(inWindow.containsAll(disagree), "disagreement outside the window: ${disagree - inWindow}")
            if (disagree.isNotEmpty()) windowCases++

            if (inWindow.isEmpty()) {
                agreeingDays++
                assertEquals(buildDay(s, a, base.n).blocks, buildDay(s, b, base.n).blocks)
            }
        }
        assertTrue(windowCases > 50, "the generator rarely reached a disagreement: $windowCases")
        assertTrue(agreeingDays > 50, "the generator rarely avoided the window: $agreeingDays")
    }

    @Test
    fun theWindowsEdgesAreExact() {
        val s = testSettings(alignToGrid = false, epochHours = 1, startMinuteOfDay = 0, graceHours = 6)
        val g = s.graceMs
        val cutoff = Epochs.epochStart(s, 0L) - g
        val items = listOf(movie('1', 20), movie('2', 20))

        // d = S - 2G: device A sees it at once (deletedAt S - 2G, before the cut-off: out); device B sees it a full G later
        // (deletedAt S - G, at the cut-off: in).
        fun pools(d: Long): Pair<Set<String>, Set<String>> {
            val seenAtOnce = items.map { if (it.id == idOf('2')) it.copy(deletedAtMs = d) else it }
            val seenLate = items.map { if (it.id == idOf('2')) it.copy(deletedAtMs = d + g) else it }
            return EpochPool
                .build(s, seenAtOnce, 0L)
                .map {
                    it.id.value
                        .first()
                        .toString()
                }.toSet() to
                EpochPool
                    .build(s, seenLate, 0L)
                    .map {
                        it.id.value
                            .first()
                            .toString()
                    }.toSet()
        }
        val (atOnceFirst, lateFirst) = pools(cutoff - g)
        assertEquals(setOf("1"), atOnceFirst)
        assertEquals(setOf("1", "2"), lateFirst)
        // d = S - G - 1 (the last moment of the window): the same disagreement.
        val (atOnceLast, lateLast) = pools(cutoff - 1)
        assertEquals(setOf("1"), atOnceLast)
        assertEquals(setOf("1", "2"), lateLast)
        // d = S - 2G - 1: even a G delay leaves deletedAt < S - G, so both leave the item out.
        val (atOnceBefore, lateBefore) = pools(cutoff - g - 1)
        assertEquals(atOnceBefore, lateBefore)
        // d = S - G: both are at or after the cut-off, so both keep the item.
        val (atOnceAfter, lateAfter) = pools(cutoff)
        assertEquals(atOnceAfter, lateAfter)
        assertEquals(setOf("1", "2"), atOnceAfter)
        // And a disagreement in the pool is a different lineup.
        val fromA = buildDay(s, items.map { if (it.id == idOf('2')) it.copy(deletedAtMs = cutoff - g) else it }, 0L)
        val fromB = buildDay(s, items.map { if (it.id == idOf('2')) it.copy(deletedAtMs = cutoff) else it }, 0L)
        assertNotEquals(fromA.blocks, fromB.blocks)
    }
}
