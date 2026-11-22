// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.GrownUpHold
import tv.stationbreak.core.engine.KidHold
import tv.stationbreak.core.engine.Pool
import tv.stationbreak.core.engine.PoolCounts
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.RatingTier
import tv.stationbreak.core.engine.passesCeiling
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.RatingLadder

/**
 * The pool of a leftovers channel: every snapshot item whose ID is not in `claimed`, under the rating ceiling exactly
 * as `PoolEvaluator` applies it. The caller decides what is claimed. Items are in snapshot order. Counts: nothing
 * matched by source, `blockedByCeiling` and `episodesWithUnknownSeries` as `PoolEvaluator` counts them,
 * `collectionsSynced` from the snapshot, no unresolved entries. Under `PoolPolicy.KidsOnly`, an unclaimed item that
 * is not in the kids tier (`KidHold.admits`) is dropped and counted in `heldOut`, and the kid ceiling replaces the
 * ladder; under `PoolPolicy.GrownUp` an unclaimed item in the kids tier is dropped and counted in `madeForKidsOut`;
 * both as `PoolEvaluator` does.
 *
 * Under `PoolPolicy.GrownUp`, and under `PoolPolicy.Custom` (here only the grown-up leftovers while no kids leftovers
 * channel is on: the kids-tier hold is lifted, the rest is not), a leftovers pool also drops (#1078, counted in
 * `blockedByCeiling`) every Explicit title, which only After Hours airs, every title with no metadata
 * (`GrownUpHold.hasNoMetadata`), which only a source that matches it can air, and every episode whose series the
 * snapshot lacks (`GrownUpHold.seriesMissing`); and under a young ceiling it keeps only
 * that band's tiers (`GrownUpHold.tierLimit`: Odds & Ends 66 the family tier, 99 family and teen). Under
 * `PoolPolicy.ExplicitOnly` it is a leftovers pool over the Explicit tier: anything else is `heldOut`.
 */
object Leftovers {
    private const val NOT_ON_LADDER = "The rating ceiling is not on the ladder"

    /**
     * Throws [IllegalArgumentException] when [maxRating] is not on the rating ladder, as `PoolEvaluator` does. [policy]
     * applies the app's narrowing (ADR-0019, ADR-0020).
     */
    fun pool(
        snapshot: RuleSnapshot,
        claimed: Set<CanonicalId>,
        maxRating: String?,
        policy: PoolPolicy,
    ): Pool {
        require(maxRating == null || maxRating in RatingLadder.names) { NOT_ON_LADDER }
        val tally = Tally(policy, maxRating)
        val items = snapshot.items
        for (index in items.indices) {
            val entry = items[index]
            if (entry.item.id in claimed) continue
            val seriesId = entry.item.seriesId
            tally.visit(entry, if (seriesId == null) null else snapshot.series[seriesId])
        }
        return Pool(
            items = tally.kept,
            counts =
                PoolCounts(
                    matchedBySource = emptyList(),
                    excluded = 0,
                    blockedByCeiling = tally.blocked,
                    episodesWithUnknownSeries = tally.unknownSeries,
                    unresolvedSources = emptyList(),
                    unresolvedExclusions = emptyList(),
                    collectionsSynced = snapshot.collectionsSynced,
                    heldOut = tally.heldOut,
                    madeForKidsOut = tally.madeForKidsOut,
                ),
        )
    }

    // The counters of one pool: a plain class with no lambdas, as the evaluator's pass.
    private class Tally(
        private val policy: PoolPolicy,
        private val maxRating: String?,
    ) {
        private val tierLimit = GrownUpHold.tierLimit(maxRating)
        val kept = ArrayList<ScheduleItem>()
        var blocked = 0
        var unknownSeries = 0
        var heldOut = 0
        var madeForKidsOut = 0

        fun visit(
            entry: RuleItem,
            series: RuleSeries?,
        ) {
            when (policy) {
                PoolPolicy.Open -> {
                    admit(entry, series)
                }

                PoolPolicy.KidsOnly -> {
                    if (KidHold.admits(entry, series)) admit(entry, series) else heldOut++
                }

                PoolPolicy.Custom, PoolPolicy.GrownUp -> {
                    val tier = RatingTier.of(entry, series)
                    when {
                        tier == RatingTier.Kids && policy == PoolPolicy.GrownUp -> {
                            madeForKidsOut++
                        }

                        tier == RatingTier.Explicit ||
                            GrownUpHold.hasNoMetadata(entry, series) ||
                            GrownUpHold.seriesMissing(entry, series) -> {
                            blocked++
                        }

                        else -> {
                            admit(entry, series, tier)
                        }
                    }
                }

                PoolPolicy.ExplicitOnly -> {
                    if (RatingTier.of(entry, series) == RatingTier.Explicit) admit(entry, series) else heldOut++
                }
            }
        }

        // The ceiling, and for a grown-up leftovers pool, whose [tier] is given, the ceiling's tier limit.
        private fun admit(
            entry: RuleItem,
            series: RuleSeries?,
            tier: RatingTier? = null,
        ) {
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
    }
}
