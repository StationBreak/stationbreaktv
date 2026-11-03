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
 * The `genres`, `years` and `kind` parts of `filters` (docs/rules-format.md, "Filters"): every part given must pass.
 * Genres are matched as `genre` sources are (any of the names, an episode also takes its series' genres), by a
 * [SourceMatcher] over synthetic `genre` entries. The `runtime` part is not here: it is a pool condition of the
 * scheduler. Built once per evaluation; [passes] allocates nothing (no lambdas: ART 7.1 crash, #264).
 */
internal class ItemFilters(
    filters: Filters?,
    snapshot: RuleSnapshot,
) {
    init {
        // The version 3 parts are matched from Task 6; until then a filter that has one must not pass everything.
        check(filters == null || !filters.usesVersion3) { "format 3 is scheduled from Task 7" }
    }

    private val genres: SourceMatcher? =
        filters?.genres?.let { names -> SourceMatcher(names.map { Source.Genre(it) }, snapshot) }
    private val yearFrom: Int? = filters?.yearFrom
    private val yearTo: Int? = filters?.yearTo
    private val kind: FilterKind? = filters?.kind

    fun passes(
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean = passesKind(entry) && passesYears(entry) && (genres == null || genres.matches(entry, series))

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
