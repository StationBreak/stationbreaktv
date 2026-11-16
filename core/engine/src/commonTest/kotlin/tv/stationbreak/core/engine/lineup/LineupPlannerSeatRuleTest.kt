// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.rules
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val STARTERS = 55
private const val FIVE = 5

/**
 * The seat rule (#785, spec "The seat rule"): the planner's own picks are capped at [LineupDefaults.PLANNER_PICKS]; a
 * channel it would not have picked (custom, edited, opt-in, a pinned starter outside the top) costs no seat.
 */
class LineupPlannerSeatRuleTest {
    private val noon = 12 * 3_600_000L
    private val boundary = nextDayStart(noon)

    private fun themed(recipe: Recipe): Recipe =
        recipe.copy(ordering = OrderingMode.Curated, theme = Theme(emptyList(), emptyList(), null, false))

    // 55 starters that each fill alone in a genre of their own; ties go to the lower number, so r00..r47 are the top.
    private val keys = List(STARTERS) { "r" + it.toString().padStart(2, '0') }
    private val recipes = keys.mapIndexed { i, k -> themed(recipeOf(k, 110 + i)) }
    private val catalog = TestCatalog(recipes, selectsByIdentity = true)
    private val library: RuleSnapshot = libraryOf(keys.associateWith { QUALIFYING_ITEMS })
    private val picks = keys.take(LineupDefaults.PLANNER_PICKS)
    private val waiting = keys.drop(LineupDefaults.PLANNER_PICKS)

    private fun first(): Lineup = planOf(null, library, catalog).also { assertTrue(it.finalised) }

    private fun replan(
        lineup: Lineup,
        snapshot: RuleSnapshot = library,
        c: StarterCatalogView = catalog,
        nowMs: Long = noon,
    ): Lineup = planOf(lineup, snapshot, c, nowMs = nowMs, boundaryMs = boundary)

    private fun edit(
        lineup: Lineup,
        edit: LineupEdit,
        c: StarterCatalogView = catalog,
    ): Lineup = assertIs<EditOutcome.Applied>(LineupEdits.apply(lineup, edit, noon, boundary, c)).lineup

    private fun custom(n: Int): LineupChannel =
        LineupChannel(
            ChannelId("custom:$n"),
            defaultNumber = null,
            numberOverride = 600 + n,
            nameOverride = null,
            state = ChannelState.On,
            source = ChannelSource.Custom(rules(listOf(Source.Genre("none")))),
            onSinceMs = 0L,
        )

    private fun Lineup.withCustoms(count: Int): Lineup = copy(channels = channels + (0 until count).map(::custom))

    // On the air and not leaving.
    private fun Lineup.staying(key: String): Boolean {
        val channel = channel(key)
        return channel.state == ChannelState.On && channel.leavesAtMs == null
    }

    private fun Lineup.starting(key: String): Boolean {
        val channel = channel(key)
        return channel.state == ChannelState.On && channel.onSinceMs == boundary
    }

    private fun Lineup.onCount(): Int = channels.count { it.state == ChannelState.On && it.leavesAtMs == null }

    @Test
    fun aFullLineupHoldsThePlannersPicksAndBothLeftovers() {
        val lineup = first()
        assertEquals(picks.toSet() + setOf("kids-odds", "odds"), lineup.onKeys())
        for (key in waiting) assertEquals(ShelfReason.BelowCap, lineup.channel(key).shelfReason, key)
    }

    @Test
    fun switchingOnFiveShelfStartersShelvesNoneOfThePlannersPicksAtTheBoundary() {
        var lineup = first()
        for (key in waiting.take(FIVE)) lineup = edit(lineup, LineupEdit.SwitchOn(LineupIds.starter(key)))

        val next = replan(lineup)

        for (key in picks) {
            assertTrue(next.staying(key), key)
            assertNull(next.channel(key).shelfReason, key)
        }
        for (key in waiting.take(FIVE)) assertTrue(next.staying(key), "the viewer's $key stays, outside the top")
        assertEquals(LineupDefaults.PLANNER_PICKS + FIVE + 2, next.onCount())
        assertEquals(lineup, next, "nothing at all changes at the boundary")
    }

    @Test
    fun aCustomChannelShelvesNoneOfThePlannersPicks() {
        val next = replan(first().withCustoms(FIVE))

        for (key in picks) assertTrue(next.staying(key), key)
        assertEquals(LineupDefaults.PLANNER_PICKS + FIVE + 2, next.onCount())
        // Even a great many of them.
        val crowded = replan(first().withCustoms(LineupDefaults.CAP * 2))
        for (key in picks) assertTrue(crowded.staying(key), key)
    }

    @Test
    fun renumberingOrSwappingPicksInsideTheTopLeavesTheOnCountUnchanged() {
        val before = first()
        val renumbered = edit(before, LineupEdit.SetNumber(LineupIds.starter("r10"), 180))
        val swapped =
            edit(
                renumbered,
                LineupEdit.SetNumber(
                    LineupIds.starter("r11"),
                    before.channel("r12").number!!,
                    swapWith = LineupIds.starter("r12"),
                ),
            )
        assertTrue(swapped.channel("r10").chosenByViewer && swapped.channel("r11").chosenByViewer)

        val next = replan(swapped)

        assertEquals(before.onCount(), next.onCount())
        assertEquals(before.onKeys(), next.onKeys())
        for (key in picks) assertTrue(next.staying(key), key)
        for (key in waiting) assertEquals(ShelfReason.BelowCap, next.channel(key).shelfReason, key)
    }

    // A lineup the older rule left (five picks shelved for five channels that took seats) grows at the first boundary
    // after the update by exactly those five: two custom, one edited, one opt-in and one outside-top pinned channel.
    @Test
    fun theFirstBoundaryAfterTheUpdateGrowsTheLineupByExactlyTheChannelsThatCostNoSeat() {
        val late = themed(recipeOf("late", 520, optIn = true))
        val withLate = TestCatalog(recipes + late, selectsByIdentity = true)
        val snapshot = libraryOf(keys.associateWith { QUALIFYING_ITEMS } + ("late" to QUALIFYING_ITEMS))
        var lineup = planOf(null, snapshot, withLate)
        lineup = lineup.withCustoms(2)
        lineup = edit(lineup, LineupEdit.SwitchOn(LineupIds.starter("late"), confirmedOptIn = true), withLate)
        lineup = edit(lineup, LineupEdit.SwitchOn(LineupIds.starter(waiting[0])), withLate)
        lineup =
            lineup.withChannel(waiting[1]) {
                it.copy(state = ChannelState.On, source = it.starter.copy(edited = true), onSinceMs = 0L)
            }
        // The older rule: five of the picks left for them.
        val gone = picks.takeLast(FIVE)
        for (key in gone) {
            lineup = lineup.withChannel(key) { it.copy(state = ChannelState.Shelf, shelfReason = ShelfReason.BelowCap) }
        }
        val before = lineup.onCount()

        val next = replan(lineup, snapshot, withLate)

        for (key in gone) assertTrue(next.starting(key), "$key is back from the boundary")
        assertEquals(before + FIVE, next.channels.count { it.state == ChannelState.On })
        for (key in picks - gone.toSet()) assertTrue(next.staying(key), key)
    }

    @Test
    fun anOptInPinAlwaysSitsOnTopAndTakesNoSeat() {
        // The opt-in starter fills far more than any pick would, so it would head the order if it were ranked.
        val late = themed(recipeOf("late", 520, optIn = true))
        val withLate = TestCatalog(recipes + late, selectsByIdentity = true)
        val snapshot = libraryOf(keys.associateWith { QUALIFYING_ITEMS } + ("late" to QUALIFYING_ITEMS * 3))
        val on = LineupEdit.SwitchOn(LineupIds.starter("late"), confirmedOptIn = true)
        val lineup = edit(planOf(null, snapshot, withLate), on, withLate)

        val next = replan(lineup, snapshot, withLate)

        assertTrue(next.staying("late"))
        for (key in picks) assertTrue(next.staying(key), key)
        assertEquals(LineupDefaults.PLANNER_PICKS + 1 + 2, next.onCount())
    }

    @Test
    fun aResetWithKeptCustomChannelsAndTheNextBoundaryAgree() {
        val kept = (0 until FIVE).map(::custom)
        val boundaryMs = nextDayStart(1_000L)
        val reset =
            LineupPlanner.plan(null, SERVER, USER, 0, library, NO_NAMES, 1_000L, boundaryMs, catalog, kept = kept)

        assertEquals(LineupDefaults.PLANNER_PICKS + FIVE + 2, reset.channels.count { it.state == ChannelState.On })
        for (key in picks) assertEquals(ChannelState.On, reset.channel(key).state, key)

        val next = planOf(reset, library, catalog, nowMs = noon, boundaryMs = boundary)
        assertEquals(reset, next, "the boundary changes nothing a reset left")
    }

    // A starter the viewer switched on takes titles in the homing as any other: here an opt-in one holds every title a
    // pick would air, so the pick is too thin to stay, and goes to the shelf with that reason.
    @Test
    fun aSwitchOnThatThinsAPlannersPickBelowQualifyingShelvesThatPickAsTooThin() {
        val shared = themed(recipeOf("shared", 105, optIn = true, sources = listOf(Source.Genre("r00"))))
        val withShared = TestCatalog(recipes + shared, selectsByIdentity = true)
        val on = LineupEdit.SwitchOn(LineupIds.starter("shared"), confirmedOptIn = true)
        val lineup = edit(planOf(null, library, withShared), on, withShared)
        assertTrue(lineup.staying("r00"))

        val next = replan(lineup, library, withShared)

        assertTrue(next.staying("shared"))
        assertEquals(boundary, next.channel("r00").leavesAtMs)
        assertEquals(ShelfReason.TooThin, next.channel("r00").shelfReason)
        assertFalse(next.staying("r00"))
        // The seat it had goes to the next best starter, from the boundary.
        assertTrue(next.starting(waiting[0]))
    }
}
