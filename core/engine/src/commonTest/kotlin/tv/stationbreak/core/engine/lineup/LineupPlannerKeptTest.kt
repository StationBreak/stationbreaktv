// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.rules
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A first choice made around kept channels (a reset keeps the viewer's own): spec 22, rulings 23 and 24. */
class LineupPlannerKeptTest {
    private val five = listOf("alpha" to 110, "bravo" to 120, "charlie" to 210, "delta" to 310, "echo" to 410)
    private val catalog = TestCatalog(five.map { (k, n) -> recipeOf(k, n) } + recipeOf("tots", 10))
    private val fills = catalog.recipes.associate { it.key to QUALIFYING_ITEMS }

    private fun kept(
        name: String,
        number: Int?,
        state: ChannelState = ChannelState.On,
    ) = LineupChannel(
        ChannelId("custom:$name"),
        null,
        number,
        null,
        state,
        ChannelSource.Custom(rules(listOf(Source.Genre("drama"))).copy(name = name, seed = Seed(7uL))),
        5L,
    )

    private fun plan(
        kept: List<LineupChannel>,
        catalog: StarterCatalogView = this.catalog,
        fills: Map<String, Int> = this.fills,
    ): Lineup =
        LineupPlanner.plan(
            null,
            SERVER,
            USER,
            0,
            libraryOf(fills),
            NO_NAMES,
            1_000L,
            catalog,
            kept = kept,
        )

    @Test
    fun thePlannerCountsKeptNumbersAsTaken() {
        val mine = kept("mine", 110)

        val lineup = plan(listOf(mine))

        assertTrue(mine in lineup.channels, "kept as it was")
        assertEquals(110, lineup.channels.first { it.id == mine.id }.number)
        // alpha's default is held by a kept channel: it takes the first number free in its own band, 100.
        val alpha = lineup.channel("alpha")
        assertEquals(100, alpha.number)
        assertEquals(ChannelState.On, alpha.state)
        assertEquals(
            lineup.channels.size,
            lineup.channels
                .mapNotNull { it.number }
                .toSet()
                .size,
        )
        // The others keep their defaults.
        assertEquals(120, lineup.channel("bravo").number)
    }

    @Test
    fun aStarterGivesWayToTheFirstNumberFreeInItsOwnBandNotAnotherStartersDefault() {
        // 100 is free, but a starter whose default is 100 would be displaced by taking it.
        val crowded = TestCatalog(catalog.recipes + recipeOf("zulu", 100))

        val lineup = plan(listOf(kept("mine", 110)), crowded, crowded.recipes.associate { it.key to QUALIFYING_ITEMS })

        assertEquals(100, lineup.channel("zulu").number)
        assertEquals(101, lineup.channel("alpha").number)
    }

    @Test
    fun aStarterWhoseBandIsFullIsShelved() {
        val band = Band.General.numbers.map { kept("g$it", it) }
        // Enough recipes elsewhere to finalise without the two whose band is full.
        val wider = TestCatalog(catalog.recipes + recipeOf("foxtrot", 220) + recipeOf("golf", 230))

        val lineup = plan(band, wider, wider.recipes.associate { it.key to QUALIFYING_ITEMS })

        val alpha = lineup.channel("alpha")
        assertEquals(ChannelState.Shelf, alpha.state)
        assertNull(alpha.number)
        assertTrue(band.all { it in lineup.channels })
        assertEquals(ChannelState.Shelf, lineup.channel("odds").state, "a leftovers channel gives way the same way")
    }

    @Test
    fun aKidsKeptChannelStaysInTheKidsBand() {
        val mine = kept("mine", 10)

        val lineup = plan(listOf(mine))

        assertEquals(10, lineup.channels.first { it.id == mine.id }.number)
        val tots = lineup.channel("tots").number
        assertTrue(tots != null && tots in Band.Kids.numbers && tots != 10, "tots moved within the Kids band: $tots")
    }

    @Test
    fun keptOnChannelsCountTowardTheCapAndNeverGiveWay() {
        val keys = List(60) { "r" + it.toString().padStart(2, '0') }
        val many = TestCatalog(keys.mapIndexed { i, k -> recipeOf(k, 200 + i) })
        val mine = (0 until 40).map { kept("m$it", 600 + it) }

        val lineup = plan(mine, many, keys.associateWith { QUALIFYING_ITEMS })

        val on = lineup.channels.filter { it.state == ChannelState.On }
        assertEquals(LineupDefaults.CAP, on.size)
        assertTrue(mine.all { it in lineup.channels && it.state == ChannelState.On })
        // 2 leftovers + 40 kept leave 8 starters, the best by key (ties broken by key).
        assertEquals(8, on.count { it.source is ChannelSource.Starter })
    }

    @Test
    fun keptOffChannelsDoNotCount() {
        val mine = (0 until 10).map { kept("m$it", 600 + it, ChannelState.UserHidden) }
        val keys = List(60) { "r" + it.toString().padStart(2, '0') }
        val many = TestCatalog(keys.mapIndexed { i, k -> recipeOf(k, 200 + i) })

        val lineup = plan(mine, many, keys.associateWith { QUALIFYING_ITEMS })

        assertEquals(LineupDefaults.CAP, lineup.channels.count { it.state == ChannelState.On })
        assertTrue(mine.all { it in lineup.channels })
    }

    @Test
    fun belowTheThresholdTheLineupHoldsOnlyTheKeptChannels() {
        val mine = kept("mine", 600)

        val lineup = plan(listOf(mine), fills = emptyMap())

        assertFalse(lineup.finalised)
        assertEquals(listOf(mine), lineup.channels)
    }

    @Test
    fun anUnfinalisedLineupKeepsItsOwnCustomChannelsWhenPlannedAgain() {
        val mine = kept("mine", 600)
        val before = plan(listOf(mine), fills = emptyMap())

        val after =
            LineupPlanner.plan(before, SERVER, USER, 0, libraryOf(fills), NO_NAMES, 2_000L, catalog)

        assertTrue(after.finalised)
        assertTrue(mine in after.channels)
    }

    @Test
    fun aStarterWhoseIdIsKeptIsNotPlannedTwice() {
        val retired = kept("x", 600).copy(id = LineupIds.starter("alpha"))

        val lineup = plan(listOf(retired))

        assertEquals(1, lineup.channels.count { it.id == LineupIds.starter("alpha") })
        assertTrue(retired in lineup.channels)
    }
}
