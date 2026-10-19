// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.RatingEligibility
import tv.stationbreak.core.rules.SeriesState
import tv.stationbreak.core.rules.Source

/**
 * Works out which items a channel's rules select (docs/rules-format.md, "Items and the pool", rules 1 to 4).
 *
 * 1. Only movies and episodes are items; `RuleSnapshot.items` holds nothing else.
 * 2. An item matches a source as `SourceMatcher` reads rule 2 (no recursion into collections; a season's own genres and
 *    tags are never used; a name matches as in "String matching").
 * 3. The pool is every item that matches any source, once, minus every item that matches any exclusion.
 * 4. Under `maxRating`, a movie needs its own rating to pass, and an episode needs its effective rating and its series'
 *    rating to pass: [RatingEligibility.isEpisodeEligible], with [SeriesState.NoSeries] for an episode with no series ID,
 *    [SeriesState.Unknown] when the ID is set but the snapshot has no such series, and [SeriesState.Known] otherwise.
 *
 * **No time filter.** This does not look at creation, deletion or runtime. An item with a deletion time is kept, and
 * which items count for which epoch is `EpochPool`'s decision.
 *
 * **Deterministic.** The result depends only on the arguments: no clock, no randomness, no locale. The pool is in the
 * order of `snapshot.items`. It is one pass over the items with the matchers built once, and nothing is cached between
 * calls, so it is safe to call from any thread.
 */
object PoolEvaluator {
    /**
     * The pool of [rules] over [snapshot], with its counts. Throws [IllegalArgumentException] naming the ID when two
     * items of the snapshot share one, whether or not either would be kept.
     */
    fun evaluate(
        rules: ChannelRules,
        snapshot: RuleSnapshot,
    ): Pool {
        val items = snapshot.items
        val sourceMatcher = SourceMatcher(rules.sources, snapshot)
        val exclusionMatcher = SourceMatcher(rules.effectiveExclusions, snapshot)
        val maxRating = rules.maxRating
        val matched = IntArray(rules.sources.size)
        var excluded = 0
        var blocked = 0
        var unknownSeries = 0
        val seen = HashSet<String>(items.size * 2)
        val kept = ArrayList<ScheduleItem>()

        for (index in items.indices) {
            val entry = items[index]
            val item = entry.item
            require(seen.add(item.id.value)) { "Duplicate item ID ${item.id.value}: an item may be given only once" }
            val seriesId = item.seriesId
            val series = if (seriesId == null) null else snapshot.series[seriesId]

            if (!sourceMatcher.tally(entry, series, matched)) continue
            if (exclusionMatcher.matches(entry, series)) {
                excluded++
                continue
            }
            val isEpisode = item.kind == ScheduleItemKind.Episode
            if (isEpisode && seriesId != null && series == null) unknownSeries++

            val allowed =
                if (maxRating == null) {
                    true
                } else if (isEpisode) {
                    val state =
                        if (seriesId == null) {
                            SeriesState.NoSeries
                        } else if (series == null) {
                            SeriesState.Unknown
                        } else {
                            SeriesState.Known(series.officialRating)
                        }
                    RatingEligibility.isEpisodeEligible(entry.officialRating, state, maxRating)
                } else {
                    RatingEligibility.isMovieEligible(entry.officialRating, maxRating)
                }
            if (allowed) kept.add(item) else blocked++
        }

        return Pool(
            items = kept,
            counts =
                PoolCounts(
                    matchedBySource = matched.toList(),
                    excluded = excluded,
                    blockedByCeiling = blocked,
                    episodesWithUnknownSeries = unknownSeries,
                    unresolvedSources = unresolvedSources(rules.sources, snapshot),
                    collectionsSynced = snapshot.collectionsSynced,
                ),
        )
    }

    private fun unresolvedSources(
        sources: List<Source>,
        snapshot: RuleSnapshot,
    ): List<Int> {
        val unresolved = ArrayList<Int>()
        for (index in sources.indices) {
            val missing =
                when (val source = sources[index]) {
                    is Source.Library -> source.id !in snapshot.libraries
                    is Source.Collection -> source.id !in snapshot.collections
                    is Source.Genre, is Source.Tag, is Source.YearRange, is Source.Series -> false
                }
            if (missing) unresolved.add(index)
        }
        return unresolved
    }
}
