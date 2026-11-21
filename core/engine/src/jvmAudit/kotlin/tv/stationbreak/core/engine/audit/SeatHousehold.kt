// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // The shape of a library is its data: counts, minutes and years are written as numbers.

package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.Timing

/**
 * A household whose library fills most of the catalog, so the planner's lineup is full (its 48 picks, and starters
 * left on the shelf behind them), with the viewer's own choices on top: five starters switched on from the shelf, a
 * custom channel, three picks hidden on the first day and switched on again after the boundary, and two starters kept
 * (switched off before they aired) and switched on again then. It is there for the seat rule (#785) and for the
 * viewer's added channels (ADR-0023): before them those channels cost the planner seats, and each hidden or kept
 * starter that is back in the top took one of its picks.
 *
 * Every titled shelf is made to be a candidate of one recipe, from the catalog's own sources (no name is written here),
 * so the library fills what the catalog can use. The titles are invented, as in every profile.
 */
internal object SeatHousehold {
    /**
     * Five starters that fill but wait on the shelf behind the planner's 48 picks, which the viewer switched on. Not
     * noir-nights: once three picks are hidden its place is a planner pick's (it is among the next in line), so
     * switching it on is no addition. case-files stays on the shelf through the whole timeline.
     */
    val SWITCHED_ON = listOf("case-files", "corsets-and-candlelight", "paradox", "masala-hour", "graveyard-shift")

    /** Three of the planner's picks, hidden on the first day and switched on again after the boundary. */
    val HIDDEN_THEN_ON = listOf("punchline", "rabbit-ears", "shag-carpet")

    /** How many of the picks that start at the boundary the viewer switches off before they air, and on again. */
    const val KEPT_THEN_ON = 2

    fun build(): Profile {
        val b = LibraryBuilder(SEAT_LIBRARY)
        for (recipe in StarterCatalog.recipes) {
            if (!recipe.optIn) shelfFor(b, recipe)
        }
        val description =
            "A household whose library fills most of the catalog: a full lineup, five starters the viewer switched " +
                "on from the shelf, three picks hidden and switched on again, two kept starters switched on and a " +
                "custom channel of their own"
        val custom = listOf(CustomChannel("My comedies", customRules("Comedy")))
        val viewer = Viewer(SWITCHED_ON, custom, HIDDEN_THEN_ON, KEPT_THEN_ON)
        return Profile("seat-household", description, AuditInput(b.build(), viewer), emptyList())
    }

    private fun shelfFor(
        b: LibraryBuilder,
        recipe: Recipe,
    ) {
        val sourced = recipe.sources.filterIsInstance<Source.Genre>().map { it.name }
        val genres = sourced + recipe.filters?.genres.orEmpty()
        val kids = recipe.kidSafe
        // kid-hold-6 (ADR-0028): a TV-Y rating alone is family, so a kid shelf carries a kids tag as its evidence.
        val kidsTag = if (kids) listOf("kids") else emptyList()
        val tags = recipe.sources.filterIsInstance<Source.Tag>().map { it.name } + kidsTag
        val studios = recipe.sources.filterIsInstance<Source.Studio>().map { it.name }
        val years = recipe.sources.filterIsInstance<Source.YearRange>().firstOrNull()
        // A Family channel (catalog 8) airs family titles: PG, no kids evidence; the other grown-up shelves are TV-14.
        val rating =
            if (kids) {
                "TV-Y"
            } else if (recipe.band == Band.Family) {
                "PG"
            } else {
                "TV-14"
            }
        val minutes = if (kids) KIDS_MINUTES else SHOW_MINUTES
        val spec = TitleSpec(genres.distinct(), tags, studios, rating, minutes, years?.from ?: SHELF_YEAR)
        repeat(SHELF_TITLES) { b.series(spec, SEASONS, EPISODES) }
    }

    private fun customRules(genre: String) =
        ChannelRules(
            formatVersion = 1,
            name = null,
            seed = Seed(0uL),
            sources = listOf(Source.Genre(genre)),
            exclusions = null,
            maxRating = null,
            ordering = null,
            timing = Timing(null, null, null, null, null, null, BroadcastDay(0, null)),
            filler = null,
        )

    private const val SEAT_LIBRARY = 7
    private const val SHELF_TITLES = 8
    private const val SEASONS = 3
    private const val EPISODES = 12
    private const val SHOW_MINUTES = 44
    private const val KIDS_MINUTES = 22
    private const val SHELF_YEAR = 2008
}
