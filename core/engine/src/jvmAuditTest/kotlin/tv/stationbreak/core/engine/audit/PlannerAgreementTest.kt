// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // Test data: the numbers are the fixtures' values.

package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.LineupDefaults
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.PlannerSelection
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.engine.lineup.ViewerChoice
import tv.stationbreak.core.engine.lineup.curation.Selection
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The audit reads the planner's own choices, not copies of them: they agree, and a drift fails loudly. */
class PlannerAgreementTest {
    // The audit's own call of the selection, made as the planner makes it (`PlannerSelection`): every candidate pinned,
    // added or free, the viewer's switch-ons pinned, an added one or an opt-in one never ranked, and the planner's
    // picks as the room.
    private fun chosenAsThePlannerDoes(planned: PlannedLineup): Set<ChannelId> {
        val lanes = AuditLanes(planned)
        val byId = planned.lineup.channels.associateBy { it.id }
        val candidates =
            lanes.channels.filter { c -> PlannerSelection.isCandidate(byId.getValue(c.id), planned.planning) }
        val ids = candidates.map { it.id }
        val pinned =
            ids.filterTo(HashSet()) {
                val channel = byId.getValue(it)
                channel.chosenByViewer && channel.state == ChannelState.On
            }
        val optIn =
            ids.filterTo(HashSet()) {
                val source = byId.getValue(it).source as ChannelSource.Starter
                planned.planning.recipe(source.recipeKey)?.optIn == true
            }
        val added = pinned.filterTo(HashSet()) { byId.getValue(it).viewerChoice == ViewerChoice.Added }
        val room = LineupDefaults.PLANNER_PICKS
        return Selection.select(planned.snapshot, candidates, pinned, optIn, room, added).on.toSet()
    }

    // What the planner put on for the day the report describes: the starters on and not leaving at its boundary.
    private fun plannersOnSet(planned: PlannedLineup): Set<ChannelId> =
        planned.lineup.channels
            .filter { it.source is ChannelSource.Starter && it.state == ChannelState.On && it.leavesAtMs == null }
            .mapTo(HashSet()) { it.id }

    @Test
    fun theAuditsSelectionOfTheStartersIsThePlannersOnSetForEveryFinalisedProfile() {
        var finalised = 0
        for (profile in ProfileLibraries.all()) {
            // Without the viewer's choices, then with them (the profile that has any).
            for (viewer in listOf(null, profile.input.viewer).distinct()) {
                val planned = PlannedLineup.of(profile.input.snapshot, CollectionNames(emptyMap()), viewer)
                if (!planned.finalised) continue
                if (viewer == null) finalised++
                val label = "${profile.key} with the viewer: ${viewer != null}"
                assertEquals(plannersOnSet(planned), chosenAsThePlannerDoes(planned), label)
            }
        }
        assertTrue(finalised >= 4, "Most profiles are finalised ($finalised)")
    }

    // The seat rule (#785) and the viewer's added channels on the profile built for them: the five switch-ons from the
    // shelf, the three hidden picks switched on again and the two kept starters switched on again, and a custom
    // channel, cost the planner none of its 48 picks. All ten are on beside them, each marked as an addition.
    @Test
    fun theSeatHouseholdKeepsAllFortyEightPicksBesideTheViewersChannels() {
        val profile = ProfileLibraries.build("seat-household")
        val none = CollectionNames(emptyMap())
        val planned = PlannedLineup.of(profile.input.snapshot, none, profile.input.viewer)
        val added =
            planned.lineup.channels
                .filter { it.viewerChoice == ViewerChoice.Added }
                .map { it.id }
                .toSet()
        val wanted = (SeatHousehold.SWITCHED_ON + SeatHousehold.HIDDEN_THEN_ON).map { LineupIds.starter(it) }.toSet()
        assertEquals(
            SeatHousehold.SWITCHED_ON.size + SeatHousehold.HIDDEN_THEN_ON.size + SeatHousehold.KEPT_THEN_ON,
            added.size,
            "every switch-on the viewer made adds a channel",
        )
        assertTrue(added.containsAll(wanted), "the shelf and the hidden starters are the viewer's additions")
        val bareOn = plannersOnSet(PlannedLineup.of(profile.input.snapshot, none, null))
        assertEquals(LineupDefaults.PLANNER_PICKS, bareOn.size, "a full lineup")
        val free =
            planned.lineup.channels.filter {
                it.source is ChannelSource.Starter && it.state == ChannelState.On && it.leavesAtMs == null &&
                    it.viewerChoice == ViewerChoice.None
            }
        assertEquals(
            LineupDefaults.PLANNER_PICKS,
            free.size,
            "the planner's own picks stay at the room (counted: a title an addition homes can move a pick)",
        )
        val custom = planned.lineup.channels.single { it.source is ChannelSource.Custom }
        assertEquals(ChannelState.On, custom.state)
    }

    @Test
    fun aStudioThatMatchesAThemeStudioReachesTheStudioTerm() {
        // For every recipe with theme studios, a series made to be its candidate whose studio is one of them. The names
        // come from the catalog itself; none is written here.
        val b = LibraryBuilder(9)
        for (recipe in StarterCatalog.recipes.filter {
            it.theme
                ?.themeStudios
                .orEmpty()
                .isNotEmpty()
        }) {
            val studio = checkNotNull(recipe.theme).themeStudios.first().folded
            val sourced = recipe.sources.filterIsInstance<Source.Genre>().map { it.name }
            val genres = sourced + recipe.filters?.genres.orEmpty()
            val tags = recipe.sources.filterIsInstance<Source.Tag>().map { it.name }
            b.series(TitleSpec(genres.distinct(), tags, listOf(studio), "TV-MA", 30, 2010), 3, 10)
        }
        val planned = PlannedLineup.of(b.build(), CollectionNames(emptyMap()), null)
        val lanes = AuditLanes(planned)
        var studioTerms = 0
        for (lane in lanes.lanes) {
            // termsOf checks, inside, that the terms add up to the lane's score.
            for (p in lane.cand.indices) if (lanes.termsOf(lane, p).q16[STUDIO_TERM] != 0L) studioTerms++
        }
        assertTrue(studioTerms > 0, "A theme studio scores the studio term")
    }

    private companion object {
        const val STUDIO_TERM = 3
    }
}
