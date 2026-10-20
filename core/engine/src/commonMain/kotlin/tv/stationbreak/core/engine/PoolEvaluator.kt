// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.RatingEligibility
import tv.stationbreak.core.rules.RatingLadder
import tv.stationbreak.core.rules.SeriesState
import tv.stationbreak.core.rules.Source

/**
 * Works out which items a channel's rules select (docs/rules-format.md, "Items and the pool", rules 1 to 4).
 *
 * 1. Only movies and episodes are items; `RuleSnapshot.items` holds nothing else.
 * 2. An item matches a source as `SourceMatcher` reads rule 2 (no recursion into collections; a season's own genres and
 *    tags are never used; a name matches as in "String matching").
 * 3. The pool is every item that matches any source, once, minus every item that matches any exclusion. Exclusions fail
 *    closed: if a `collection` exclusion is not in the snapshot the pool is empty, and a `genre` or `tag` exclusion
 *    also removes an episode whose series is missing from the snapshot.
 * 4. Under `maxRating`, a movie needs its own rating to pass, and an episode needs its effective rating and its series'
 *    rating to pass: [RatingEligibility.isEpisodeEligible], with [SeriesState.NoSeries] for an episode with no series
 *    ID, [SeriesState.Unknown] when the ID is set but the snapshot has no such series, and [SeriesState.Known]
 *    otherwise.
 *
 * **No time filter.** This does not look at creation, deletion or runtime. An item with a deletion time is kept, and
 * which items count for which epoch is `EpochPool`'s decision.
 *
 * **Deterministic.** The result depends only on the arguments: no clock, no randomness, no locale. The pool is in the
 * order of `snapshot.items`. It is one pass over the items with the matchers built once, and nothing is cached between
 * calls, so it is safe to call from any thread.
 */
object PoolEvaluator {
    private const val NOT_ON_LADDER = "The rating ceiling is not on the ladder"

    /**
     * The pool of [rules] over [snapshot], with its counts. Throws [IllegalArgumentException]:
     *  - naming the ID, when two items of the snapshot share one, whether or not either would be kept;
     *  - when `rules.maxRating` is not on the rating ladder, whatever the items (so even an empty snapshot throws).
     */
    fun evaluate(
        rules: ChannelRules,
        snapshot: RuleSnapshot,
    ): Pool {
        val ceiling = rules.maxRating
        require(ceiling == null || ceiling in RatingLadder.names) { NOT_ON_LADDER }
        val pass = Pass(rules, snapshot)
        val items = snapshot.items
        for (index in items.indices) pass.visit(items[index])
        return Pool(
            items = pass.kept,
            counts =
                PoolCounts(
                    matchedBySource = pass.matched.toList(),
                    excluded = pass.excluded,
                    blockedByCeiling = pass.blocked,
                    episodesWithUnknownSeries = pass.unknownSeries,
                    unresolvedSources = unresolvedCollections(rules.sources, snapshot),
                    unresolvedExclusions = pass.unresolvedExclusions,
                    collectionsSynced = snapshot.collectionsSynced,
                ),
        )
    }

    // The state of one evaluation: the matchers are built once, and every count is a plain counter updated in the one
    // pass. No lambdas in [visit], which runs once per item (ART 7.1 crash, #264).
    private class Pass(
        rules: ChannelRules,
        private val snapshot: RuleSnapshot,
    ) {
        private val sourceMatcher = SourceMatcher(rules.sources, snapshot)
        private val exclusionMatcher =
            SourceMatcher(rules.effectiveExclusions, snapshot, missingSeriesMatchesNames = true)

        // Exclusions fail closed: one that cannot be checked removes everything (rule 3 as amended).
        val unresolvedExclusions = unresolvedCollections(rules.effectiveExclusions, snapshot)
        private val excludeAll = unresolvedExclusions.isNotEmpty()
        private val maxRating = rules.maxRating
        private val seen = HashSet<String>(snapshot.items.size * 2)
        val matched = IntArray(rules.sources.size)
        val kept = ArrayList<ScheduleItem>()
        var excluded = 0
        var blocked = 0
        var unknownSeries = 0

        fun visit(entry: RuleItem) {
            val item = entry.item
            require(seen.add(item.id.value)) { "Duplicate item ID ${item.id.value}: an item may be given only once" }
            val seriesId = item.seriesId
            val series = if (seriesId == null) null else snapshot.series[seriesId]
            if (!sourceMatcher.tally(entry, series, matched)) return
            if (excludeAll || exclusionMatcher.matches(entry, series)) {
                excluded++
                return
            }
            if (item.kind == ScheduleItemKind.Episode && seriesId != null && series == null) unknownSeries++
            if (passesCeiling(entry, series, maxRating)) kept.add(item) else blocked++
        }
    }

    // The indices of the collection entries of [entries] whose collection is not in the snapshot.
    private fun unresolvedCollections(
        entries: List<Source>,
        snapshot: RuleSnapshot,
    ): List<Int> {
        val unresolved = ArrayList<Int>()
        for (index in entries.indices) {
            val entry = entries[index]
            if (entry is Source.Collection && entry.id !in snapshot.collections) unresolved.add(index)
        }
        return unresolved
    }
}
