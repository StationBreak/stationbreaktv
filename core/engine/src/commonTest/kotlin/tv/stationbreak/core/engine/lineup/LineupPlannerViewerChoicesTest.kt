// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The planner never undoes what the viewer chose: a switch, or a number. */
class LineupPlannerViewerChoicesTest {
    private val five = listOf("alpha" to 110, "bravo" to 120, "charlie" to 210, "delta" to 310, "echo" to 410)
    private val base = TestCatalog(five.map { (k, n) -> recipeOf(k, n) } + recipeOf("tots", 10))

    private fun fills(catalog: StarterCatalogView) = catalog.recipes.associate { it.key to QUALIFYING_ITEMS }

    private fun firstLineup(catalog: StarterCatalogView = base): Lineup =
        planOf(null, libraryOf(fills(catalog)), catalog).also { assertTrue(it.finalised) }

    private fun edit(
        lineup: Lineup,
        edit: LineupEdit,
        catalog: StarterCatalogView = base,
    ): Lineup = assertIs<EditOutcome.Applied>(LineupEdits.apply(lineup, edit, 50L, catalog)).lineup

    private fun replan(
        lineup: Lineup,
        catalog: StarterCatalogView,
        synced: Boolean = true,
    ): Lineup = planOf(lineup, libraryOf(fills(catalog), collectionsSynced = synced), catalog, nowMs = 9_000L)

    @Test
    fun aNewRecipeWhoseDefaultTheViewerHoldsJoinsTheShelfNumberless() {
        val held = edit(firstLineup(), LineupEdit.SetNumber(LineupIds.starter("alpha"), 150))
        val v2 = TestCatalog(base.recipes + recipeOf("nova", 150), version = 2)
        val next = replan(held, v2)
        val nova = next.channel("nova")
        assertEquals(ChannelState.Shelf, nova.state)
        assertNull(nova.number)
        assertEquals(150, next.channel("alpha").numberOverride)
        assertEquals(150, next.channel("alpha").number)
    }

    @Test
    fun aChannelTheViewerSwitchedOffStaysOff() {
        val off = edit(firstLineup(), LineupEdit.SwitchOff(LineupIds.starter("delta")))
        assertEquals(ChannelState.UserHidden, off.channel("delta").state)
        assertEquals(ChannelState.UserHidden, replan(off, base).channel("delta").state)
        val v2 = TestCatalog(base.recipes + recipeOf("nova", 160), version = 2)
        assertEquals(ChannelState.UserHidden, replan(off, v2).channel("delta").state)
    }

    @Test
    fun channelsTheViewerSwitchedOnAboveTheCapStayOn() {
        val keys = List(LineupDefaults.CAP + 10) { "r" + it.toString().padStart(2, '0') }
        val catalog = TestCatalog(keys.mapIndexed { i, k -> recipeOf(k, 200 + i) })
        var lineup = firstLineup(catalog)
        val shelf = lineup.channels.filter { it.state == ChannelState.Shelf }
        assertTrue(shelf.size >= 3)
        shelf.take(3).forEach { lineup = edit(lineup, LineupEdit.SwitchOn(it.id), catalog) }
        val on =
            lineup.channels
                .filter { it.state == ChannelState.On }
                .map { it.id }
                .toSet()
        assertEquals(LineupDefaults.CAP + 3, on.size)

        val v2 = TestCatalog(catalog.recipes + recipeOf("nova", 270), version = 2)
        for (next in listOf(replan(lineup, catalog), replan(lineup, v2), replan(lineup, v2, synced = false))) {
            val nextOn =
                next.channels
                    .filter { it.state == ChannelState.On }
                    .map { it.id }
                    .toSet()
            assertTrue(nextOn.containsAll(on), "every switched-on channel stays on")
            assertEquals(on, nextOn - LineupIds.starter("nova"), "no shelf channel switches on")
            // An unsynced call holds the newer catalog back, so "nova" may not be on the lineup yet.
            val nova = next.channels.firstOrNull { it.id == LineupIds.starter("nova") }
            assertTrue(nova == null || nova.state == ChannelState.Shelf)
        }
    }

    @Test
    fun aViewerNumberSurvivesARecipeLeavingTheCatalog() {
        val held = edit(firstLineup(), LineupEdit.SetNumber(LineupIds.starter("charlie"), 250))
        val v2 = TestCatalog(base.recipes.filter { it.key != "charlie" }, version = 2)
        val after = replan(held, v2).channel("charlie")
        assertIs<ChannelSource.Custom>(after.source)
        assertEquals(250, after.numberOverride)
        assertEquals(250, after.number)
    }

    @Test
    fun aViewerSwitchOnOfANumberlessStarterKeepsItsNumber() {
        val held = edit(firstLineup(), LineupEdit.SetNumber(LineupIds.starter("alpha"), 150))
        val v2 = TestCatalog(base.recipes + recipeOf("nova", 150), version = 2)
        val joined = replan(held, v2)
        assertNull(joined.channel("nova").number)
        val on = edit(joined, LineupEdit.SwitchOn(LineupIds.starter("nova")), v2)
        val chosen = assertNotNull(on.channel("nova").numberOverride)
        val next = replan(on, v2)
        assertEquals(ChannelState.On, next.channel("nova").state)
        assertEquals(chosen, next.channel("nova").numberOverride)
        assertEquals(150, next.channel("alpha").numberOverride)
    }
}
