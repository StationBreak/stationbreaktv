// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
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
 *    tags are never used; a name matches as in "String matching"). A studio name matches under studio fold 1 in a
 *    file that says `formatVersion` 1 to 3 and under studio fold 2 in one that says 4 (ADR-0026, with its empty-key
 *    rule for exclusions).
 * 3. The pool is every item that matches any source, once, minus every item that matches any exclusion. Exclusions fail
 *    closed: if a `collection` exclusion is not in the snapshot the pool is empty, and a `genre` or `tag` exclusion
 *    also removes an episode whose series is missing from the snapshot.
 * 4. Under `maxRating`, a movie needs its own rating to pass, and an episode needs its effective rating and its series'
 *    rating to pass: [RatingEligibility.isEpisodeEligible], with [SeriesState.NoSeries] for an episode with no series
 *    ID, [SeriesState.Unknown] when the ID is set but the snapshot has no such series, and [SeriesState.Known]
 *    otherwise.
 *
 * **Policies.** Under [PoolPolicy.KidsOnly] (a kid channel, spec 18), an item that survives the exclusions must also be
 * in the kids tier ([KidHold.admits], ADR-0028) and within the kid ceiling ([KidHold.passesKidCeiling]) in place of the
 * ladder (ADR-0019). Under [PoolPolicy.GrownUp] it must not be something a kid channel could take
 * ([GrownUpHold.dropsFromGrownUp]: the kids tier, counted in `madeForKidsOut`, or the Explicit tier, counted in
 * `blockedByCeiling`); the ladder applies as usual (ADR-0020), and under a young ceiling so does its tier limit
 * ([GrownUpHold.tierLimit], #1078; a title above it is `blockedByCeiling`). Under [PoolPolicy.Custom] with a young
 * ceiling that tier limit applies too, and nothing else of the grown-up hold (#1108, `grown-up-hold-9`). Under
 * [PoolPolicy.ExplicitOnly] (After
 * Hours, #1078) every Explicit title counts as matched by a source (it is not in `matchedBySource`), and then the
 * kids tier is dropped as under [PoolPolicy.GrownUp]. This is the app's narrowing, not rules-format behaviour: rules
 * format v1 knows nothing of it, and [PoolPolicy.Open] is exactly the evaluation above.
 *
 * **Filters.** The `genres`, `years` and `kind` parts of `rules.filters` apply to what remains after the exclusions and
 * the policy hold, before the ceiling ([ItemFilters]). The `runtime` part is the scheduler's (`EpochPool`).
 *
 * **What a pool depends on.** The snapshot, the sources, the exclusions, the filters, the ceiling, the policy and the
 * format version (studio fold 1 or 2): a caller that caches a pool keys it on all of them.
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
     *
     * [policy] applies the app's narrowing after the exclusions (see the class comment); [PoolPolicy.Open] is the
     * rules-format evaluation.
     */
    fun evaluate(
        rules: ChannelRules,
        snapshot: RuleSnapshot,
        policy: PoolPolicy = PoolPolicy.Open,
    ): Pool {
        val ceiling = rules.maxRating
        require(ceiling == null || ceiling in RatingLadder.names) { NOT_ON_LADDER }
        val pass = Pass(rules, snapshot, policy)
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
                    heldOut = pass.heldOut,
                    madeForKidsOut = pass.madeForKidsOut,
                    filteredOut = pass.filteredOut,
                ),
        )
    }

    // The state of one evaluation: the matchers are built once, and every count is a plain counter updated in the one
    // pass. No lambdas in [visit], which runs once per item (ART 7.1 crash, #264).
    private class Pass(
        rules: ChannelRules,
        private val snapshot: RuleSnapshot,
        private val policy: PoolPolicy,
    ) {
        // A file that says 4 matches studios under studio fold 2 (ADR-0026); 1 to 3 under fold 1.
        private val fold2 = rules.matchesStudiosByFold2
        private val sourceMatcher = SourceMatcher(rules.sources, snapshot, studioFold2 = fold2)
        private val exclusionMatcher =
            SourceMatcher(
                rules.effectiveExclusions,
                snapshot,
                missingSeriesMatchesNames = true,
                studioFold2 = fold2,
                emptyStudioKeyMatches = true,
            )

        // Exclusions fail closed: one that cannot be checked removes everything (rule 3 as amended).
        val unresolvedExclusions = unresolvedCollections(rules.effectiveExclusions, snapshot)
        private val excludeAll = unresolvedExclusions.isNotEmpty()
        private val maxRating = rules.maxRating
        private val tierLimit = GrownUpHold.tierLimit(maxRating)
        private val filters = ItemFilters(rules.filters, snapshot, fold2)

        // The IDs seen so far, built only once the items stop being in strictly ascending ID order: a snapshot is
        // canonical, so for it every ID is new by construction and no hash set is needed (one set per channel before).
        private val seen = HashSet<String>()
        private var unordered = false
        private var previousId: String? = null
        private var visited = 0
        val matched = IntArray(rules.sources.size)
        val kept = ArrayList<ScheduleItem>()
        var excluded = 0
        var blocked = 0
        var unknownSeries = 0
        var heldOut = 0
        var madeForKidsOut = 0
        var filteredOut = 0

        fun visit(entry: RuleItem) {
            val item = entry.item
            requireNew(item.id.value)
            val seriesId = item.seriesId
            val series = if (seriesId == null) null else snapshot.series[seriesId]
            val sourced = sourceMatcher.tally(entry, series, matched)
            // After Hours takes every Explicit title as if a source matched it.
            val explicitOnAfterHours =
                policy == PoolPolicy.ExplicitOnly && RatingTier.of(entry, series) == RatingTier.Explicit
            if (!sourced && !explicitOnAfterHours) return
            if (excludeAll || exclusionMatcher.matches(entry, series)) {
                excluded++
            } else {
                applyPolicy(entry, series)
            }
        }

        // The app's narrowing: an exhaustive when, so a new policy fails to compile until it is handled here.
        private fun applyPolicy(
            entry: RuleItem,
            series: RuleSeries?,
        ) {
            when (policy) {
                PoolPolicy.Open -> {
                    admit(entry, series)
                }

                // A custom channel under a young ceiling (PG or TV-14 or less) airs what that band's built-in channels
                // air: its tier limit applies (#1108), so PG-rated horror or a gore tag stays out as it does from the
                // Family band. Above that, or with no ceiling, it is open.
                PoolPolicy.Custom -> {
                    admit(entry, series, if (tierLimit < RatingTier.Adult) RatingTier.of(entry, series) else null)
                }

                PoolPolicy.KidsOnly -> {
                    if (KidHold.admits(entry, series)) admit(entry, series) else heldOut++
                }

                PoolPolicy.GrownUp -> {
                    val tier = RatingTier.of(entry, series)
                    when {
                        tier == RatingTier.Kids -> {
                            madeForKidsOut++
                        }

                        // Explicit: above every grown-up channel's limit (After Hours alone airs it). An episode whose
                        // series is missing may hide an Explicit series: dropped with no ceiling (a ceiling blocks it).
                        GrownUpHold.drops(tier) || (maxRating == null && GrownUpHold.seriesMissing(entry, series)) -> {
                            blocked++
                        }

                        else -> {
                            admit(entry, series, tier)
                        }
                    }
                }

                PoolPolicy.ExplicitOnly -> {
                    if (KidHold.admits(entry, series)) madeForKidsOut++ else admit(entry, series)
                }
            }
        }

        // What matched, was not excluded and is not held out: dropped by the filters, or counted, then kept or blocked
        // by the ceiling (and, for a grown-up channel, whose [tier] is given, by the ceiling's tier limit).
        private fun admit(
            entry: RuleItem,
            series: RuleSeries?,
            tier: RatingTier? = null,
        ) {
            if (!filters.passes(entry, series)) {
                filteredOut++
                return
            }
            val item = entry.item
            if (item.kind == ScheduleItemKind.Episode && item.seriesId != null && series == null) unknownSeries++
            val passes =
                when (policy) {
                    PoolPolicy.KidsOnly -> {
                        KidHold.passesKidCeiling(entry, series, maxRating)
                    }

                    PoolPolicy.Open -> {
                        passesCeiling(entry, series, maxRating)
                    }

                    PoolPolicy.Custom, PoolPolicy.GrownUp, PoolPolicy.ExplicitOnly -> {
                        passesCeiling(entry, series, maxRating, approvedIsUnrated = true)
                    }
                }
            if (passes && (tier == null || tier <= tierLimit)) kept.add(item) else blocked++
        }

        private fun requireNew(id: String) {
            val before = previousId
            previousId = id
            visited++
            if (!unordered) {
                if (before == null || before < id) return
                // The first step out of order: everything before it was ascending, so unique; start the set from it.
                unordered = true
                for (index in 0 until visited - 1) {
                    seen.add(
                        snapshot.items[index]
                            .item.id.value,
                    )
                }
            }
            require(seen.add(id)) { "Duplicate item ID $id: an item may be given only once" }
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
