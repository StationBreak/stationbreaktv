// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A change to what an unedited starter airs starts at the next day boundary (spec 23, D1, F2). */
class BoundaryRulesTest {
    private val keys = listOf("alpha", "bravo", "charlie", "delta", "echo")
    private val base = TestCatalog(keys.mapIndexed { i, k -> recipeOf(k, 110 + i * 10) })
    private val noon = 12 * 3_600_000L
    private val boundary = nextDayStart(noon)

    private fun fills(catalog: StarterCatalogView) = catalog.recipes.associate { it.key to QUALIFYING_ITEMS }

    private fun first(): Lineup = planOf(null, libraryOf(fills(base)), base).also { assertTrue(it.finalised) }

    // A newer catalog whose "alpha" recipe is about another genre: the planner resolves it again.
    private fun changed(genre: String = "other"): TestCatalog =
        TestCatalog(
            base.recipes.map {
                if (it.key == "alpha") recipeOf("alpha", 110, sources = listOf(Source.Genre(genre))) else it
            },
            version = 2,
        )

    private fun replan(
        lineup: Lineup,
        catalog: TestCatalog,
        nowMs: Long = noon,
        boundaryMs: Long = boundary,
    ) = planOf(lineup, libraryOf(fills(catalog)), catalog, nowMs = nowMs, boundaryMs = boundaryMs)

    private fun rulesOf(key: String) = RecipeResolver.resolve(recipeOf(key, 110), SERVER, 0, NO_NAMES, NO_LIBRARY)

    private fun starterOf(rules: ChannelRules) = ChannelSource.Starter("alpha", edited = false, rules = rules)

    @Test
    fun aRulesChangeAppliesFromTheBoundary() {
        val lineup = first()
        val old = lineup.channel("alpha").starter.rules
        val next = replan(lineup, changed())
        val starter = next.channel("alpha").starter
        assertEquals(old, starter.rulesFor(boundary - 1))
        assertEquals(listOf<Source>(Source.Genre("other")), starter.rulesFor(boundary).sources)
        assertEquals(boundary, starter.rulesFromMs)
        assertEquals(old, starter.previousRules)
    }

    @Test
    fun aSecondChangeBeforeTheBoundaryKeepsTheFirstPrevious() {
        val lineup = first()
        val old = lineup.channel("alpha").starter.rules
        val once = replan(lineup, changed("one"))
        val twice = replan(once, TestCatalog(changed("two").recipes, version = 3), nowMs = noon + 1_000L)
        val starter = twice.channel("alpha").starter
        assertEquals(old, starter.previousRules)
        assertEquals(listOf<Source>(Source.Genre("two")), starter.rules.sources)
        assertEquals(boundary, starter.rulesFromMs)
        // Back to the previous rules before the boundary: nothing is pending any more.
        val back = starterOf(old.copy(sources = listOf(Source.Genre("one")))).withPrevious(old, boundary)
        val reverted = back.withNewRules(old, noon, boundary)
        assertNull(reverted.previousRules)
        assertNull(reverted.rulesFromMs)
    }

    @Test
    fun aChangeAfterTheBoundaryStartsAnotherOne() {
        val old = rulesOf("alpha")
        val first = starterOf(old.copy(sources = listOf(Source.Genre("one")))).withPrevious(old, boundary)
        val next = first.withNewRules(old.copy(sources = listOf(Source.Genre("two"))), boundary + 1, boundary + DAY_MS)
        assertEquals(listOf<Source>(Source.Genre("one")), next.previousRules?.sources)
        assertEquals(boundary + DAY_MS, next.rulesFromMs)
    }

    @Test
    fun unchangedRulesSetNoBoundary() {
        val lineup = first()
        val next = replan(lineup, TestCatalog(base.recipes, version = 2))
        for (channel in next.channels) {
            val source = channel.source as? ChannelSource.Starter ?: continue
            assertNull(source.previousRules, channel.id.value)
            assertNull(source.rulesFromMs, channel.id.value)
        }
        assertNull(next.curationBasis)
    }

    @Test
    fun thePlannerUsesTheBoundaryItIsGiven() {
        val next = replan(first(), changed(), boundaryMs = 3 * DAY_MS + DAY_START_MS)
        assertEquals(3 * DAY_MS + DAY_START_MS, next.channel("alpha").starter.rulesFromMs)
    }

    @Test
    fun withNewRulesRequiresUnchangedTiming() {
        val old = rulesOf("alpha")
        val timed = old.copy(timing = old.timing.copy(blockLength = 3))
        assertFailsWith<IllegalArgumentException> { starterOf(old).withNewRules(timed, noon, boundary) }
        val edited = ChannelSource.Starter("alpha", edited = true, rules = old)
        assertFailsWith<IllegalArgumentException> { edited.withNewRules(old.copy(name = "Other"), noon, boundary) }
    }

    @Test
    fun withNewRulesRequiresAFutureDayStartBoundary() {
        val old = rulesOf("alpha")
        val next = old.copy(sources = listOf(Source.Genre("other")))
        assertFailsWith<IllegalArgumentException> { starterOf(old).withNewRules(next, boundary, boundary) }
        assertFailsWith<IllegalArgumentException> { starterOf(old).withNewRules(next, noon, boundary - 3_600_000L) }
        assertEquals(boundary, starterOf(old).withNewRules(next, noon, boundary).rulesFromMs)
    }

    @Test
    fun aShelvedStarterSwitchedOnTakesItsNewRulesAtOnce() {
        val shelved = first().withChannel("alpha") { it.copy(state = ChannelState.Shelf) }
        val catalog = changed("bravo")
        val next = replan(shelved, catalog).channel("alpha")
        assertEquals(ChannelState.On, next.state)
        assertNull(next.starter.previousRules)
        assertNull(next.starter.rulesFromMs)
        assertEquals(listOf<Source>(Source.Genre("bravo")), next.starter.rulesFor(noon).sources)
        // A starter that stays on keeps today's rules until the boundary.
        val stays = replan(first(), catalog).channel("alpha").starter
        assertEquals(boundary, stays.rulesFromMs)
    }

    @Test
    fun withNewRulesLeavesTheStarterAloneWhenNothingChanged() {
        val source = starterOf(rulesOf("alpha"))
        assertSame(source, source.withNewRules(source.rules, noon, boundary))
    }

    @Test
    fun backToBackAndResetApplyAtOnce() {
        val lineup = first()
        val old =
            lineup.withChannel("alpha") {
                val rules = it.starter.rules
                it.copy(source = it.starter.copy(rules = rules.copy(timing = rules.timing.copy(alignToGrid = null))))
            }
        val fixed = replan(old, TestCatalog(base.recipes, version = 1)).channel("alpha").starter
        assertEquals(false, fixed.rules.timing.alignToGrid)
        assertNull(fixed.previousRules)
        // A reset plans from nothing: no starter carries previous rules.
        val pending = replan(lineup, changed())
        assertTrue(pending.channel("alpha").starter.previousRules != null)
        val reset = planOf(null, libraryOf(fills(changed())), changed(), nowMs = noon, boundaryMs = boundary)
        assertTrue(reset.channels.mapNotNull { it.source as? ChannelSource.Starter }.all { it.previousRules == null })
    }

    @Test
    fun switchOnClearsLeavesAt() {
        val lineup = first().withChannel("alpha") { it.copy(leavesAtMs = boundary, shelfReason = ShelfReason.BelowCap) }
        val outcome = LineupEdits.apply(lineup, LineupEdit.SwitchOn(LineupIds.starter("alpha")), noon, boundary, base)
        val alpha = assertIs<EditOutcome.Applied>(outcome).lineup.channel("alpha")
        assertNull(alpha.leavesAtMs)
        assertEquals(ChannelState.On, alpha.state)
        assertTrue(alpha.chosenByViewer)
    }

    @Test
    fun anOnSetChangeRecordsTheBasisAtTheBoundary() {
        val curated = ordering(OrderingMode.Curated)
        val lineup =
            first().withChannel("alpha") {
                it.copy(source = it.starter.copy(rules = it.starter.rules.copy(formatVersion = 3, ordering = curated)))
            }
        val off = LineupEdits.apply(lineup, LineupEdit.SwitchOff(LineupIds.starter("alpha")), noon, boundary, base)
        val basis = assertIs<EditOutcome.Applied>(off).lineup.curationBasis
        assertEquals(CurationBasis(boundary, setOf(LineupIds.starter("alpha")), true), basis)
        // A switch that leaves the on-set alone records nothing.
        val plain = LineupEdits.apply(first(), LineupEdit.SwitchOff(LineupIds.starter("bravo")), noon, boundary, base)
        assertNull(assertIs<EditOutcome.Applied>(plain).lineup.curationBasis)
    }

    private fun ordering(mode: OrderingMode) = Ordering(mode)
}
