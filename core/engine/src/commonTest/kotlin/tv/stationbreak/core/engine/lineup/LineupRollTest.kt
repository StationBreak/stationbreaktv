// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What is past at a moment is taken off the lineup, once and the same way every time (`Lineup.rolled`). */
class LineupRollTest {
    private val keys = listOf("alpha", "bravo", "charlie", "delta", "echo")
    private val base = TestCatalog(keys.mapIndexed { i, k -> recipeOf(k, 110 + i * 10) })
    private val lineup: Lineup = planOf(null, libraryOf(base.recipes.associate { it.key to QUALIFYING_ITEMS }), base)
    private val alpha = LineupIds.starter("alpha")

    private fun Lineup.with(
        key: String,
        change: (LineupChannel) -> LineupChannel,
    ) = withChannel(key, change)

    private fun withPrevious(boundary: Long): Lineup =
        lineup.with("alpha") {
            val old = it.starter.rules
            val changed = it.starter.copy(rules = old.copy(sources = listOf(Source.Genre("other"))))
            it.copy(source = changed.withPrevious(old, boundary))
        }

    @Test
    fun aPastLeaveBecomesShelf() {
        val leaving = lineup.with("alpha") { it.copy(leavesAtMs = 5_000L, shelfReason = ShelfReason.BelowCap) }
        val rolled = leaving.rolled(5_000L)
        assertEquals(ChannelState.Shelf, rolled.channel("alpha").state)
        assertNull(rolled.channel("alpha").leavesAtMs)
        assertEquals(ShelfReason.BelowCap, rolled.channel("alpha").shelfReason)
        // Not yet due: nothing changes.
        assertSame(leaving, leaving.rolled(4_999L))
    }

    @Test
    fun pastPreviousRulesAreDropped() {
        val pending = withPrevious(5_000L)
        assertSame(pending, pending.rolled(4_999L))
        val rolled = pending.rolled(5_000L)
        assertNull(rolled.channel("alpha").starter.previousRules)
        assertNull(rolled.channel("alpha").starter.rulesFromMs)
        assertEquals(pending.channel("alpha").starter.rules, rolled.channel("alpha").starter.rules)
    }

    @Test
    fun aPastBasisIsCleared() {
        val basis = lineup.copy(curationBasis = CurationBasis(5_000L, setOf(alpha), true))
        assertSame(basis, basis.rolled(4_999L))
        assertNull(basis.rolled(5_000L).curationBasis)
    }

    @Test
    fun rollingIsIdempotent() {
        val everything =
            withPrevious(5_000L)
                .with("bravo") { it.copy(leavesAtMs = 4_000L) }
                .copy(curationBasis = CurationBasis(5_000L, setOf(ChannelId("starter:bravo")), false))
        val once = everything.rolled(6_000L)
        assertEquals(once, once.rolled(6_000L))
        assertSame(once, once.rolled(6_000L))
        assertTrue(once != everything)
    }
}
