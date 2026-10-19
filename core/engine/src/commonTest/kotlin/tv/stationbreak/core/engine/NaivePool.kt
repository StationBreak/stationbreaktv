// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.RatingLadder
import tv.stationbreak.core.rules.Source

// The reference the property test compares the evaluator with. It is written straight from docs/rules-format.md ("Items
// and the pool", rules 1 to 4 as amended on 2026-10-19, and "String matching") and from nothing in the evaluator.
//
// It must NEVER be "optimised": no precomputed sets, no maps, no early exits shared with the evaluator. For each item
// it loops over every source and every exclusion, and it reads the amended rule 4 itself. It uses RatingLadder.rankOf
// for the tier lookup and for nothing else. Its speed does not matter. Its being obviously like the doc does.

/** What the reference works out. The fields are the ones the evaluator's counts also carry. */
internal data class NaiveResult(
    val items: List<ScheduleItem>,
    val matchedBySource: List<Int>,
    val excluded: Int,
    val blockedByCeiling: Int,
    val episodesWithUnknownSeries: Int,
    val unresolvedSources: List<Int>,
    val unresolvedExclusions: List<Int>,
)

@Suppress("CyclomaticComplexMethod", "ReturnCount", "LoopWithTooManyJumpStatements") // Deliberately long and flat.
internal object NaivePool {
    fun evaluate(
        rules: ChannelRules,
        snapshot: RuleSnapshot,
    ): NaiveResult {
        val kept = mutableListOf<ScheduleItem>()
        val matchedBySource = MutableList(rules.sources.size) { 0 }
        var excluded = 0
        var blocked = 0
        var unknown = 0
        val unresolvedExclusions = unresolvedExclusions(rules, snapshot)
        for (entry in snapshot.items) {
            var matchesAny = false
            for ((index, source) in rules.sources.withIndex()) {
                if (matches(source, entry, snapshot, asExclusion = false)) {
                    matchedBySource[index] = matchedBySource[index] + 1
                    matchesAny = true
                }
            }
            if (!matchesAny) continue
            // Rule 3 as amended: when an exclusion that is a collection cannot be checked (it is not in the snapshot),
            // nothing airs, so every item that matched a source is excluded.
            var excludedHere = unresolvedExclusions.isNotEmpty()
            for (exclusion in rules.effectiveExclusions) {
                if (matches(exclusion, entry, snapshot, asExclusion = true)) excludedHere = true
            }
            if (excludedHere) {
                excluded++
                continue
            }
            val item = entry.item
            val seriesId = item.seriesId
            if (item.kind == ScheduleItemKind.Episode && seriesId != null && snapshot.series[seriesId] == null) {
                unknown++
            }
            if (!passesCeiling(entry, snapshot, rules.maxRating)) {
                blocked++
                continue
            }
            kept.add(item)
        }
        val unresolvedSources = unresolved(rules, snapshot)
        return NaiveResult(kept, matchedBySource, excluded, blocked, unknown, unresolvedSources, unresolvedExclusions)
    }

    private fun unresolved(
        rules: ChannelRules,
        snapshot: RuleSnapshot,
    ): List<Int> {
        val out = mutableListOf<Int>()
        for ((index, source) in rules.sources.withIndex()) {
            val missing =
                when (source) {
                    is Source.Collection -> snapshot.collections[source.id] == null
                    is Source.Library, is Source.Genre, is Source.Tag, is Source.YearRange, is Source.Series -> false
                }
            if (missing) out.add(index)
        }
        return out
    }

    private fun unresolvedExclusions(
        rules: ChannelRules,
        snapshot: RuleSnapshot,
    ): List<Int> {
        val out = mutableListOf<Int>()
        for ((index, exclusion) in rules.effectiveExclusions.withIndex()) {
            if (exclusion is Source.Collection && snapshot.collections[exclusion.id] == null) out.add(index)
        }
        return out
    }

    // Rule 4. Written from the doc text, clause by clause.
    private fun passesCeiling(
        entry: RuleItem,
        snapshot: RuleSnapshot,
        maxRating: String?,
    ): Boolean {
        if (maxRating == null) return true
        val ceiling = checkNotNull(RatingLadder.rankOf(maxRating)) { "the ceiling is a ladder name" }
        val own = entry.officialRating
        val item = entry.item
        if (item.kind == ScheduleItemKind.Movie) return tierPasses(own, ceiling)
        // An episode with no series ID is checked on its own rating alone.
        val seriesId = item.seriesId ?: return tierPasses(own, ceiling)
        // A series ID that is present, with no such series in the snapshot, fails closed.
        val series = snapshot.series[seriesId] ?: return false
        val seriesRating = series.officialRating
        // (a) the effective rating is the own rating, or the series' when the own is none or the empty string.
        val effective = if (own.isNullOrEmpty()) seriesRating else own
        val a = tierPasses(effective, ceiling)
        // (b) the series' rating passes, when the series has one (anything but none or the empty string).
        val seriesHasOne = !(seriesRating.isNullOrEmpty())
        val b = if (seriesHasOne) tierPasses(seriesRating, ceiling) else true
        return a && b
    }

    private fun tierPasses(
        rating: String?,
        ceiling: Int,
    ): Boolean {
        val tier = RatingLadder.rankOf(rating) ?: return false
        return tier <= ceiling
    }

    // Rule 2.
    private fun matches(
        source: Source,
        entry: RuleItem,
        snapshot: RuleSnapshot,
        asExclusion: Boolean,
    ): Boolean {
        val item = entry.item
        val isEpisode = item.kind == ScheduleItemKind.Episode
        val seriesId = item.seriesId
        val seasonId = entry.seasonId
        val series: RuleSeries? = if (isEpisode && seriesId != null) snapshot.series[seriesId] else null
        // Rule 3 as amended: a genre or tag exclusion removes an episode whose series ID is set but whose series is
        // not in the snapshot, whatever its own names are.
        val seriesMissing = asExclusion && isEpisode && seriesId != null && series == null
        return when (source) {
            is Source.Library -> {
                entry.libraryId == source.id
            }

            is Source.Series -> {
                isEpisode && seriesId == source.id
            }

            is Source.Collection -> {
                val collection = snapshot.collections[source.id]
                when {
                    collection == null -> {
                        false
                    }

                    !isEpisode -> {
                        item.id in collection.movies
                    }

                    else -> {
                        item.id in collection.episodes ||
                            (seriesId != null && seriesId in collection.series) ||
                            (seasonId != null && seasonId in collection.seasons)
                    }
                }
            }

            is Source.Genre -> {
                seriesMissing ||
                    hasName(entry.genres, source.name) ||
                    (series != null && hasName(series.genres, source.name))
            }

            is Source.Tag -> {
                seriesMissing ||
                    hasName(entry.tags, source.name) ||
                    (series != null && hasName(series.tags, source.name))
            }

            is Source.YearRange -> {
                val year = item.productionYear
                year != null && year >= source.from && year <= source.to
            }
        }
    }

    private fun hasName(
        names: List<String>,
        wanted: String,
    ): Boolean {
        for (name in names) {
            if (fold(name) == fold(wanted)) return true
        }
        return false
    }

    // Only A to Z become a to z; every other character stays exactly as it is.
    private fun fold(text: String): String {
        val out = StringBuilder()
        for (ch in text) {
            if (ch in 'A'..'Z') out.append(ch + ('a' - 'A')) else out.append(ch)
        }
        return out.toString()
    }
}
