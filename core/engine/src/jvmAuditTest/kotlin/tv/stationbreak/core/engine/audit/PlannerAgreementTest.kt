// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // Test data: the numbers are the fixtures' values.

package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.LineupDefaults
import tv.stationbreak.core.engine.lineup.PlannerSelection
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.engine.lineup.curation.Selection
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The audit reads the planner's own choices, not copies of them: they agree, and a drift fails loudly. */
class PlannerAgreementTest {
    @Test
    fun theAuditsSelectionOfTheStartersIsThePlannersOnSetForEveryFinalisedProfile() {
        var finalised = 0
        for (profile in ProfileLibraries.all()) {
            val planned = PlannedLineup.of(profile.input.snapshot, CollectionNames(emptyMap()), null)
            if (!planned.finalised) continue
            finalised++
            val lanes = AuditLanes(planned)
            val candidates =
                lanes.channels.filter { c ->
                    PlannerSelection.isCandidate(planned.lineup.channels.first { it.id == c.id }, planned.planning)
                }
            val leftoversOn =
                planned.lineup.channels.count { it.source is ChannelSource.Leftovers && it.state == ChannelState.On }
            val room = LineupDefaults.CAP - leftoversOn
            val chosen = Selection.select(planned.snapshot, candidates, emptySet(), emptySet(), room)
            val planners =
                planned.lineup.channels
                    .filter { it.source is ChannelSource.Starter && it.state == ChannelState.On }
                    .mapTo(HashSet()) { it.id }
            assertEquals(planners, chosen.on.toSet(), profile.key)
        }
        assertTrue(finalised >= 4, "Most profiles are finalised ($finalised)")
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
