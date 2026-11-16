// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val STARTERS = 56
private const val THREE = 3
private const val SIX = 6

/**
 * A starter the viewer switches on adds a channel and takes no planner seat (ADR-0023, "the viewer's channels take no
 * seats"). The fixtures are 56 starters that each fill alone in a genre of their own, so no two share a title: a
 * starter joining the homing never thins another, and "no pick leaves" can be asserted. Ties go to the lower number,
 * so r00..r47 are the planner's picks. Sizes are counted as on and not leaving, as on the air after the boundary.
 *
 * Tests that fail on main (where a starter the viewer switches on is a ranked pin that takes a seat inside the top) say
 * so; the others guard what already holds.
 */
class LineupPlannerSwitchOnTest {
    private val noon = 12 * 3_600_000L
    private val boundary = nextDayStart(noon)
    private val dayTwoNoon = boundary + noon - DAY_START_MS
    private val boundaryTwo = nextDayStart(dayTwoNoon)

    private fun themed(recipe: Recipe): Recipe =
        recipe.copy(ordering = OrderingMode.Curated, theme = Theme(emptyList(), emptyList(), null, false))

    private val keys = List(STARTERS) { "r" + it.toString().padStart(2, '0') }
    private val recipes = keys.mapIndexed { i, k -> themed(recipeOf(k, 110 + i)) }
    private val catalog = TestCatalog(recipes, selectsByIdentity = true)
    private val room = LineupDefaults.PLANNER_PICKS

    private fun library(raised: Map<String, Int> = emptyMap()): RuleSnapshot =
        libraryOf(keys.associateWith { raised[it] ?: QUALIFYING_ITEMS })

    private fun first(): Lineup = planOf(null, library(), catalog).also { assertTrue(it.finalised) }

    private fun plan(
        lineup: Lineup,
        nowMs: Long,
        boundaryMs: Long,
        snapshot: RuleSnapshot = library(),
    ): Lineup = planOf(lineup, snapshot, catalog, nowMs = nowMs, boundaryMs = boundaryMs)

    private fun edit(
        lineup: Lineup,
        edit: LineupEdit,
        nowMs: Long = noon,
        boundaryMs: Long = boundary,
    ): Lineup = assertIs<EditOutcome.Applied>(LineupEdits.apply(lineup, edit, nowMs, boundaryMs, catalog)).lineup

    private fun on(key: String) = LineupEdit.SwitchOn(LineupIds.starter(key))

    private fun off(key: String) = LineupEdit.SwitchOff(LineupIds.starter(key))

    // The starters on the air once the boundary has passed: on, and not leaving by then.
    private fun Lineup.starters(at: Long): List<LineupChannel> =
        rolled(at).channels.filter { it.state == ChannelState.On && it.source is ChannelSource.Starter }

    private fun Lineup.anyLeaving(): Boolean = channels.any { it.leavesAtMs != null }

    // Day one: hide r00, r01 and r02 and let the boundary pass, so r48..r50 start in their places. Returns the
    // lineup on day two (after the boundary), where a switch-on is no longer an undo.
    private fun dayTwoWithThreeHidden(): Lineup {
        var lineup = first()
        for (key in keys.take(THREE)) lineup = edit(lineup, off(key))
        lineup = plan(lineup, noon, boundary)
        return lineup.rolled(dayTwoNoon)
    }

    // On main (and the planner before this change) the three hidden picks switched back on are ranked pins in the top.
    // FAILS ON MAIN: the lineup grows by 3, not by 0 seats lost: 3 more starters than the 48 free picks hold.
    @Test
    fun hiddenFormerPicksSwitchedOnAfterTheBoundaryAddChannelsAndShelveNoPick() {
        var lineup = dayTwoWithThreeHidden()
        val before = lineup.starters(dayTwoNoon).size
        assertEquals(room, before, "r03..r50 are the picks")
        for (key in keys.take(THREE)) lineup = edit(lineup, on(key), dayTwoNoon, boundaryTwo)

        val next = plan(lineup, dayTwoNoon, boundaryTwo)

        assertEquals(before + THREE, next.starters(boundaryTwo).size, "three switched on, three channels added")
        assertFalse(next.anyLeaving(), "no pick of the planner is shelved (the starters share no titles)")
    }

    // The same switch-on on the same day is an undo: the pick comes back with its mark, and the room is as it was.
    @Test
    fun aPickSwitchedOffAndBackOnTheSameDayChangesNothing() {
        val start = first()
        val offOn = edit(edit(start, off("r00")), on("r00"))

        assertEquals(ViewerChoice.Touched, offOn.channel("r00").viewerChoice)
        val next = plan(offOn, noon, boundary)
        assertEquals(start.starters(boundary).size, next.starters(boundary).size)
        assertEquals(start.onKeys(), next.onKeys())
        assertFalse(next.anyLeaving())
    }

    // r48..r50 start at the boundary in place of three hidden picks; the viewer shelves r48 before it airs (a kept
    // starter), the boundary passes, and the viewer switches it on again: r48 is in the top, and takes no seat.
    // FAILS ON MAIN: r49 is shelved to give r48 its seat.
    @Test
    fun aKeptStarterInTheTopSwitchedOnAfterTheBoundaryTakesNoSeat() {
        var lineup = edit(first(), off("r00"))
        lineup = plan(lineup, noon, boundary)
        assertEquals(ChannelState.On, lineup.channel("r48").state)
        lineup = edit(lineup, off("r48"))
        lineup = plan(lineup, noon, boundary)
        assertEquals(ChannelState.Shelf, lineup.channel("r48").state)
        assertTrue(lineup.channel("r48").chosenByViewer, "the viewer's switch-off is a choice")
        lineup = plan(lineup, dayTwoNoon, boundaryTwo).rolled(dayTwoNoon)
        val before = lineup.starters(dayTwoNoon).size

        lineup = edit(lineup, on("r48"), dayTwoNoon, boundaryTwo)
        val next = plan(lineup, dayTwoNoon, boundaryTwo)

        assertEquals(before + 1, next.starters(boundaryTwo).size)
        assertFalse(next.anyLeaving(), "no pick of the planner is shelved (the starters share no titles)")
    }

    // A starter switched on from the shelf, outside the top, whose score then rises into the top as the library grows.
    // FAILS ON MAIN: it becomes a ranked pin inside the top and shelves r47.
    @Test
    fun aSwitchedOnStarterTakesNoSeatWhenItsScoreRisesIntoTheTop() {
        val switchedOn = edit(first(), on("r50"))
        val before = switchedOn.starters(boundary).size

        val grown = library(mapOf("r50" to QUALIFYING_ITEMS * 3))
        val next = plan(switchedOn, noon, boundary, grown)

        assertEquals(before, next.starters(boundary).size)
        assertFalse(next.anyLeaving(), "no pick of the planner is shelved (the starters share no titles)")
        assertEquals(ViewerChoice.Added, next.channel("r50").viewerChoice)
    }

    // The same through a renumber first: the starter is a kept one (renumbered, then switched on).
    // FAILS ON MAIN: as above.
    @Test
    fun aRenumberedShelfStarterSwitchedOnAddsAChannel() {
        var lineup = edit(first(), LineupEdit.SetNumber(LineupIds.starter("r50"), 181))
        val grown = library(mapOf("r50" to QUALIFYING_ITEMS * 3))
        lineup = plan(lineup, noon, boundary, grown).rolled(dayTwoNoon)
        val before = lineup.starters(dayTwoNoon).size
        assertEquals(ChannelState.Shelf, lineup.channel("r50").state)

        lineup = edit(lineup, on("r50"), dayTwoNoon, boundaryTwo)
        val next = plan(lineup, dayTwoNoon, boundaryTwo, grown)

        assertEquals(before + 1, next.starters(boundaryTwo).size)
        assertFalse(next.anyLeaving())
    }

    // An untouched starter with no number is not a candidate; switching it on gives it a number of the viewer's. Its
    // score is in the top. FAILS ON MAIN: it takes a seat as a ranked pin.
    @Test
    fun aNumberlessShelfStarterSwitchedOnTakesNoSeat() {
        val numberless =
            first().withChannel("r50") {
                it.copy(state = ChannelState.Shelf, defaultNumber = null, numberOverride = null)
            }
        val switchedOn = edit(numberless, on("r50"))
        val before = switchedOn.starters(boundary).size

        val grown = library(mapOf("r50" to QUALIFYING_ITEMS * 3))
        val next = plan(switchedOn, noon, boundary, grown)

        assertEquals(before, next.starters(boundary).size)
        assertFalse(next.anyLeaving(), "no pick of the planner is shelved (the starters share no titles)")
    }

    // The channels the viewer switches on and the ones the planner already shelved on day two: each adds exactly one.
    // FAILS ON MAIN: the three hidden picks take seats, so the lineup grows by three, not six.
    @Test
    fun switchingOnSixStartersAddsExactlySix() {
        var lineup = dayTwoWithThreeHidden()
        val before = lineup.starters(dayTwoNoon).size
        for (key in keys.take(THREE) + keys.slice(51 until 51 + THREE)) {
            lineup = edit(lineup, on(key), dayTwoNoon, boundaryTwo)
        }

        val next = plan(lineup, dayTwoNoon, boundaryTwo)

        assertEquals(before + SIX, next.starters(boundaryTwo).size)
    }

    // A numbered shelf starter below the top: already free on main (homed, ranked outside the top), so this guards it.
    @Test
    fun aNumberedBelowCapStarterSwitchedOnAddsOne() {
        val before = first().starters(boundary).size

        val next = plan(edit(first(), on("r52")), noon, boundary)

        assertEquals(before + 1, next.starters(boundary).size)
        assertFalse(next.anyLeaving())
    }

    // Guards the "a mark only goes up" rule: the starter is Added, inside the top (a rise of its score), and the
    // renumber must not turn it into a ranked pin. FAILS ON MAIN: the switch-on itself takes the seat.
    @Test
    fun renumberingAnAddedStarterInsideTheTopStillTakesNoSeat() {
        val grown = library(mapOf("r50" to QUALIFYING_ITEMS * 3))
        var lineup = plan(edit(first(), on("r50")), noon, boundary, grown).rolled(dayTwoNoon)
        val before = lineup.starters(dayTwoNoon).size
        assertTrue(before > room)

        lineup = edit(lineup, LineupEdit.SetNumber(LineupIds.starter("r50"), 181), dayTwoNoon, boundaryTwo)
        val next = plan(lineup, dayTwoNoon, boundaryTwo, grown)

        assertEquals(ViewerChoice.Added, next.channel("r50").viewerChoice)
        assertEquals(before, next.starters(boundaryTwo).size)
        assertFalse(next.anyLeaving())
    }

    // A leaving pick switched off and on the same day is an undo, and the pick stays Added: it takes no seat when its
    // score grows into the top. A pick leaves at the boundary when a recipe's items drop away: r00's fill falls.
    @Test
    fun aLeavingPickSwitchedOffAndBackOnTheSameDayStaysAddedAndTakesNoSeat() {
        val thin = library(mapOf("r00" to 0))
        val leaving = plan(first(), noon, boundary, thin)
        assertEquals(boundary, leaving.channel("r00").leavesAtMs, "r00 leaves at the boundary")

        val undone = edit(edit(leaving, off("r00")), on("r00"))
        assertEquals(ViewerChoice.Added, undone.channel("r00").viewerChoice)
        assertNull(undone.channel("r00").leavesAtMs)

        val before = undone.starters(boundary).size
        val next = plan(undone, noon, boundary, library(mapOf("r00" to QUALIFYING_ITEMS * 3)))
        assertEquals(before, next.starters(boundary).size)
        assertFalse(next.anyLeaving())
    }

    // The switch-off of a leaving pick holds Added only until the boundary passes (`Lineup.rolled`).
    @Test
    fun aHiddenAddedStarterIsLoweredToTouchedOnceTheBoundaryPasses() {
        val thin = library(mapOf("r00" to 0))
        val hidden = edit(plan(first(), noon, boundary, thin), off("r00"))
        assertEquals(ChannelState.UserHidden, hidden.channel("r00").state)
        assertEquals(ViewerChoice.Added, hidden.channel("r00").viewerChoice)

        assertEquals(ViewerChoice.Added, hidden.rolled(boundary - 1).channel("r00").viewerChoice)
        assertEquals(ViewerChoice.Touched, hidden.rolled(boundary).channel("r00").viewerChoice)
    }

    // A starting pick the viewer switches off and on again (a waiting pick) is an undo: no growth. The planner's own
    // unpick of a starting pick is not: it stamps the time, so a switch-on of it adds a channel.
    @Test
    fun aStartingPickSwitchedOffAndBackOnIsUndoneButOneThePlannerUnpickedIsNot() {
        var lineup = plan(edit(first(), off("r00")), noon, boundary)
        assertEquals(boundary, lineup.channel("r48").onSinceMs)

        val offOn = edit(edit(lineup, off("r48")), on("r48"))
        assertEquals(ViewerChoice.Touched, offOn.channel("r48").viewerChoice)

        // The library loses r48's titles before the boundary: the planner unpicks it, never aired.
        lineup = plan(lineup, noon, boundary, library(mapOf("r48" to 0)))
        assertEquals(ChannelState.Shelf, lineup.channel("r48").state)
        assertEquals(noon, lineup.channel("r48").onSinceMs, "the unpick is stamped with the time it was made")
        assertEquals(ViewerChoice.None, lineup.channel("r48").viewerChoice)
        val again = edit(lineup, on("r48"), noon, boundary)
        assertEquals(ViewerChoice.Added, again.channel("r48").viewerChoice)
    }

    @Test
    fun anOptInStarterSwitchedOnIsTouchedNeverAdded() {
        val optIn = themed(recipeOf("late", 520, optIn = true))
        val withLate = TestCatalog(catalog.recipes + optIn, selectsByIdentity = true)
        val snapshot = libraryOf(keys.associateWith { QUALIFYING_ITEMS } + ("late" to QUALIFYING_ITEMS))
        val lineup = planOf(null, snapshot, withLate)
        val switchOnLate = LineupEdit.SwitchOn(LineupIds.starter("late"), confirmedOptIn = true)
        val switched =
            assertIs<EditOutcome.Applied>(
                LineupEdits.apply(lineup, switchOnLate, noon, boundary, withLate),
            ).lineup

        assertEquals(ViewerChoice.Touched, switched.channel("late").viewerChoice)
    }

    @Test
    fun aRenumberOrSwapNeverMakesAStarterAdded() {
        val renumbered = edit(first(), LineupEdit.SetNumber(LineupIds.starter("r10"), 180))
        assertEquals(ViewerChoice.Touched, renumbered.channel("r10").viewerChoice)
        val shelf = edit(first(), LineupEdit.SetNumber(LineupIds.starter("r50"), 181))
        assertEquals(ViewerChoice.Touched, shelf.channel("r50").viewerChoice)
    }

    // A reset rebuilds the starters fresh: the marks drop.
    @Test
    fun aResetDropsTheMarks() {
        val switchedOn = edit(first(), on("r50"))
        assertEquals(ViewerChoice.Added, switchedOn.channel("r50").viewerChoice)

        val reset = LineupPlanner.plan(null, SERVER, USER, 0, library(), NO_NAMES, noon, boundary, catalog)

        assertTrue(reset.channels.none { it.viewerChoice != ViewerChoice.None })
    }

    // The recipe leaves the catalog: the starter becomes a custom channel, which cannot hold the mark.
    @Test
    fun aCatalogRemovalClearsTheMark() {
        val switchedOn = edit(first(), on("r50"))
        val withoutIt = TestCatalog(catalog.recipes.filter { it.key != "r50" }, selectsByIdentity = true, version = 2)

        val next = planOf(switchedOn, library(), withoutIt, nowMs = noon, boundaryMs = boundary)

        val channel = next.channels.first { it.id == LineupIds.starter("r50") }
        assertIs<ChannelSource.Custom>(channel.source)
        assertEquals(ViewerChoice.Touched, channel.viewerChoice)
    }

    // A mark Added on a channel that is off: Shelf counts as kept, a hidden one is no candidate and keeps the mark.
    @Test
    fun aSwitchedOffAddedStarterKeepsItsMarkAndCostsNoSeat() {
        val hidden = edit(edit(first(), on("r50")), off("r50"))
        val next = plan(hidden, noon, boundary, library(mapOf("r50" to QUALIFYING_ITEMS * 3)))

        assertEquals(ChannelState.UserHidden, next.channel("r50").state)
        assertEquals(ViewerChoice.Added, next.channel("r50").viewerChoice)
        assertFalse(next.anyLeaving())
    }
}
