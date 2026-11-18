// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.rules
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #798: a starter with no free number is NoNumber; BelowCap is left to the planner's stronger picks. */
class LineupPlannerNoNumberTest {
    private val own = listOf("a", "b").mapIndexed { i, k -> recipeOf(k, 110 + i) }
    private val numbered = List(6) { recipeOf("n$it", 200 + it) }
    private val catalog = TestCatalog(own + numbered)
    private val library = libraryOf(catalog.recipes.associate { it.key to QUALIFYING_ITEMS })

    // The viewer's own channels hold every General number, so "a" and "b" find none free.
    private val held =
        Band.General.numbers.map {
            LineupChannel(
                ChannelId("custom:$it"),
                defaultNumber = it,
                numberOverride = null,
                nameOverride = null,
                state = ChannelState.On,
                source = ChannelSource.Custom(rules(listOf(Source.Genre("none")))),
                onSinceMs = 0L,
            )
        }

    private fun first(): Lineup =
        LineupPlanner
            .plan(null, SERVER, USER, 0, library, NO_NAMES, 1_000L, nextDayStart(1_000L), catalog, kept = held)
            .also { assertTrue(it.finalised) }

    @Test
    fun theFirstChoiceGivesANumberlessStarterNoNumber() {
        val lineup = first()
        for (key in listOf("a", "b")) {
            assertEquals(ChannelState.Shelf, lineup.channel(key).state, key)
            assertEquals(ShelfReason.NoNumber, lineup.channel(key).shelfReason, key)
        }
        assertEquals(ChannelState.On, lineup.channel("n0").state)
    }

    @Test
    fun aRefreshGivesAnUntouchedNumberlessStarterNoNumberButAKeptOneItsHomedReason() {
        val prepared =
            first()
                .withChannel("a") { it.copy(shelfReason = null) }
                .withChannel("b") { it.copy(viewerChoice = ViewerChoice.Touched, shelfReason = null) }

        val next = planOf(prepared, library, catalog, nowMs = 9_000L)

        assertEquals(ShelfReason.NoNumber, next.channel("a").shelfReason, "untouched, no number")
        assertEquals(ShelfReason.BelowCap, next.channel("b").shelfReason, "kept: the homing's reason")
    }
}
