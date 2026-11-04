// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.CurationBasis
import tv.stationbreak.core.engine.lineup.EditOutcome
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.LineupEdits
import tv.stationbreak.core.engine.lineup.LineupPlanner
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.engine.lineup.ShelfReason
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** What the lineup holds in memory is what a save and a load give back, for the planner's and every edit's result. */
class LineupV3RoundTripTest {
    private val genres = listOf("Animation", "Comedy", "Crime", "Western", "Horror", "Science Fiction")
    private val library =
        RuleSnapshot(
            ADDED_MS,
            setOf(LIBRARY_ID),
            genres.flatMapIndexed { g, genre -> (1..8).map { movie(g * 100 + it, genre) } },
            emptyMap(),
            emptyMap(),
            collectionsSynced = true,
        )

    private fun plan(
        current: Lineup?,
        catalog: StarterCatalogView = TestCatalog,
    ) = LineupPlanner.plan(current, SERVER, USER, 0, library, CollectionNames(emptyMap()), 5_000L, catalog)

    private fun assertRoundTrips(
        lineup: Lineup,
        what: String,
    ) = assertEquals(
        LineupCodec.Decoded.Loaded(lineup),
        LineupCodec.decode(LineupCodec.encode(lineup)),
        "$what survives a save and a load",
    )

    private val shuffle = rules("Mine", Source.Tag("mine")).copy(ordering = Ordering(OrderingMode.Shuffle))

    // The planner's lineup with a custom channel, some choices, day-boundary fields on a starter and a basis.
    private fun planned(): Lineup {
        val first = plan(null)
        val custom = channel("custom:mine", 600, ChannelState.On, ChannelSource.Custom(shuffle))
        val channels =
            first.channels.map {
                when (it.id.value) {
                    "starter:rockets" -> it.copy(leavesAtMs = 9_000L, shelfReason = ShelfReason.BelowCap)
                    "starter:laughs" -> it.copy(chosenByViewer = true)
                    else -> it
                }
            } + custom
        val on = channels.filter { it.state == ChannelState.On }.map { it.id }.toSet()
        return first.copy(channels = channels, curationBasis = CurationBasis(7_000L, on, household = true))
    }

    @Test
    fun thePlannersLineupRoundTrips() {
        assertRoundTrips(plan(null), "a first choice")
        assertRoundTrips(planned(), "a lineup with every new field")
    }

    @Test
    fun aStarterFrozenToCustomLosesItsDayBoundaryFields() {
        val before = planned()
        val newer =
            object : StarterCatalogView by TestCatalog {
                override val version: Int = 2
                override val recipes: List<Recipe> = TestCatalog.recipes.filter { it.key != "rockets" }
            }

        val after = plan(before, newer)

        val frozen = after.channels.first { it.id.value == "starter:rockets" }
        assertIs<ChannelSource.Custom>(frozen.source)
        assertNull(frozen.leavesAtMs)
        assertNull(frozen.shelfReason)
        assertRoundTrips(after, "a catalog update that freezes a starter")
    }

    @Test
    fun everyEditsResultRoundTrips() {
        val lineup = planned()
        val laughs = ChannelId("starter:laughs")
        val edits =
            listOf(
                LineupEdit.SwitchOn(ChannelId("starter:after-hours"), confirmedOptIn = true),
                LineupEdit.SwitchOff(laughs),
                LineupEdit.SetNumber(laughs, 500),
                LineupEdit.ClearNumber(laughs),
                LineupEdit.AddCustom(ChannelId("custom:new"), shuffle, null, kids = false),
                LineupEdit.ReplaceCustom(ChannelId("custom:mine"), shuffle, null, kids = false),
                LineupEdit.Reseed(ChannelId("custom:mine"), Seed(5uL)),
                LineupEdit.RemoveCustom(ChannelId("custom:mine")),
            )
        for (edit in edits) {
            val outcome = LineupEdits.apply(lineup, edit, 6_000L, TestCatalog)
            assertRoundTrips(assertIs<EditOutcome.Applied>(outcome, "$edit applies").lineup, edit.toString())
        }
    }

    @Test
    fun removingACustomChannelForgetsItInTheBasis() {
        val lineup = planned()
        val mine = ChannelId("custom:mine")
        assertEquals(true, ChannelId("custom:mine") in checkNotNull(lineup.curationBasis).on)

        val after =
            assertIs<EditOutcome.Applied>(LineupEdits.apply(lineup, LineupEdit.RemoveCustom(mine), 6_000L, TestCatalog))

        assertEquals(false, ChannelId("custom:mine") in checkNotNull(after.lineup.curationBasis).on)
    }
}
