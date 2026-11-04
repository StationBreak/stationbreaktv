// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.rules.Ordering
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A viewer's edit moves the curation basis, so re-homing starts at the next day (spec 23). */
class CurationBasisTest {
    private val keys = listOf("alpha", "bravo", "charlie", "delta", "echo")
    private val base = TestCatalog(keys.mapIndexed { i, k -> recipeOf(k, 110 + i * 10) })
    private val noon = 12 * 3_600_000L
    private val boundary = nextDayStart(noon)
    private val allOn = keys.map { LineupIds.starter(it) }.toSet()

    // Every starter is programmed, so each one counts in the curated on-set.
    private fun curated(): Lineup {
        val first = planOf(null, libraryOf(base.recipes.associate { it.key to QUALIFYING_ITEMS }), base)
        return keys.fold(first) { lineup, key ->
            lineup.withChannel(key) {
                val rules = it.starter.rules.copy(formatVersion = 3, ordering = Ordering(OrderingMode.Curated))
                it.copy(source = it.starter.copy(rules = rules))
            }
        }
    }

    private fun apply(
        lineup: Lineup,
        edit: LineupEdit,
        nowMs: Long = noon,
        boundaryMs: Long = boundary,
    ): Lineup = assertIs<EditOutcome.Applied>(LineupEdits.apply(lineup, edit, nowMs, boundaryMs, base)).lineup

    private fun off(key: String) = LineupEdit.SwitchOff(LineupIds.starter(key))

    @Test
    fun aSwitchSetsTheBasisToTheOldOnSet() {
        val lineup = curated()
        assertNull(lineup.curationBasis)
        val basis = apply(lineup, off("alpha")).curationBasis
        assertEquals(CurationBasis(boundary, allOn, true), basis)
        val on = apply(apply(lineup, off("alpha")), LineupEdit.SwitchOn(LineupIds.starter("alpha")), noon + 1_000L)
        assertEquals(basis, on.curationBasis)
    }

    @Test
    fun aSecondChangeBeforeTheBoundaryKeepsTheFirstBasis() {
        val once = apply(curated(), off("alpha"))
        val twice = apply(once, off("bravo"), noon + 60_000L)
        assertEquals(CurationBasis(boundary, allOn, true), twice.curationBasis)
        assertEquals(ChannelState.UserHidden, twice.channel("bravo").state)
    }

    @Test
    fun aChangeAfterTheBoundaryStartsANewBasis() {
        val once = apply(curated(), off("alpha"))
        val later = boundary + 3_600_000L
        val next = nextDayStart(later)
        val twice = apply(once, off("bravo"), later, next)
        assertEquals(CurationBasis(next, allOn - LineupIds.starter("alpha"), true), twice.curationBasis)
    }

    @Test
    fun aNumberChangeSetsNoBasis() {
        val renumbered = apply(curated(), LineupEdit.SetNumber(LineupIds.starter("alpha"), 311))
        assertEquals(311, renumbered.channel("alpha").number)
        assertNull(renumbered.curationBasis)
    }

    @Test
    fun renumberingALeavingChannelKeepsThePendingBasisAndTodayUnchanged() {
        val pending = CurationBasis(boundary, allOn, true)
        val leaving =
            curated()
                .withChannel("alpha") { it.copy(leavesAtMs = boundary, shelfReason = ShelfReason.BelowCap) }
                .copy(curationBasis = pending)
        val renumbered = apply(leaving, LineupEdit.SetNumber(LineupIds.starter("alpha"), 311))
        val alpha = renumbered.channel("alpha")
        // The choice keeps it on tomorrow, so the on-set differs; the pending basis keeps today's old one.
        assertNull(alpha.leavesAtMs)
        assertNull(alpha.shelfReason)
        assertTrue(alpha.chosenByViewer)
        assertEquals(pending, renumbered.curationBasis)
        assertEquals(ChannelState.On, alpha.state)
    }

    @Test
    fun renumberingALeavingChannelWithoutABasisRecordsOne() {
        val leaving =
            curated().withChannel("alpha") { it.copy(leavesAtMs = boundary, shelfReason = ShelfReason.BelowCap) }
        val renumbered = apply(leaving, LineupEdit.SetNumber(LineupIds.starter("alpha"), 311))
        assertEquals(boundary, renumbered.curationBasis?.fromMs)
    }
}
