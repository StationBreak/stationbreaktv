// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.FilterKind
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.Source

/**
 * The `genres`, `tags`, `studios`, `notGenres`, `years` and `kind` parts of `filters` (docs/rules-format.md,
 * "Filters"): every part given must pass. Genres, tags and studios are matched as `genre`, `tag` and `studio` sources
 * are (any of the names, an episode also takes its series' names), by a [SourceMatcher] over synthetic entries.
 * Studios match under studio fold 2 when [studioFold2] (a file that says `formatVersion` 4), else under fold 1; a
 * `studios` name whose fold 2 key is empty matches nothing. `notGenres` passes an item that matches none of its
 * genres, and fails closed on an episode whose series is missing. The `runtime` part is not here: it is a pool
 * condition of the scheduler. Built once per evaluation; [passes] allocates nothing (no lambdas: ART 7.1 crash, #264).
 */
internal class ItemFilters(
    filters: Filters?,
    snapshot: RuleSnapshot,
    studioFold2: Boolean = false,
) {
    private val genres: SourceMatcher? =
        filters?.genres?.let { names -> SourceMatcher(names.map { Source.Genre(it) }, snapshot) }
    private val tags: SourceMatcher? =
        filters?.tags?.let { names -> SourceMatcher(names.map { Source.Tag(it) }, snapshot) }
    private val studios: SourceMatcher? =
        filters?.studios?.let { names ->
            SourceMatcher(names.map { Source.Studio(it) }, snapshot, studioFold2 = studioFold2)
        }
    private val notGenres: SourceMatcher? =
        filters?.notGenres?.let { names ->
            SourceMatcher(names.map { Source.Genre(it) }, snapshot, missingSeriesMatchesNames = true)
        }
    private val yearFrom: Int? = filters?.yearFrom
    private val yearTo: Int? = filters?.yearTo
    private val kind: FilterKind? = filters?.kind

    fun passes(
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean =
        passesKind(entry) &&
            passesYears(entry) &&
            (genres == null || genres.matches(entry, series)) &&
            (tags == null || tags.matches(entry, series)) &&
            (studios == null || studios.matches(entry, series)) &&
            (notGenres == null || !notGenres.matches(entry, series))

    private fun passesKind(entry: RuleItem): Boolean {
        val wanted = kind ?: return true
        val isMovie = entry.item.kind == ScheduleItemKind.Movie
        return if (wanted == FilterKind.Movie) isMovie else !isMovie
    }

    // An item with no production year fails a year filter.
    private fun passesYears(entry: RuleItem): Boolean {
        val from = yearFrom
        val to = yearTo
        if (from == null || to == null) return true
        val year = entry.item.productionYear
        return year != null && year >= from && year <= to
    }
}
