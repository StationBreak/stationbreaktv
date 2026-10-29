// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.KidHold
import tv.stationbreak.core.engine.Pool
import tv.stationbreak.core.engine.PoolCounts
import tv.stationbreak.core.engine.PoolPolicy
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
 * is not made for kids is dropped and counted in `heldOut`, and the kid rating table replaces the ladder; under
 * `PoolPolicy.GrownUp` an unclaimed item with a children's rating is dropped and counted in `childrensRatingOut`; both
 * as `PoolEvaluator` does.
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
                    childrensRatingOut = tally.childrensRatingOut,
                ),
        )
    }

    // The counters of one pool: a plain class with no lambdas, as the evaluator's pass.
    private class Tally(
        private val policy: PoolPolicy,
        private val maxRating: String?,
    ) {
        val kept = ArrayList<ScheduleItem>()
        var blocked = 0
        var unknownSeries = 0
        var heldOut = 0
        var childrensRatingOut = 0

        fun visit(
            entry: RuleItem,
            series: RuleSeries?,
        ) {
            when {
                policy == PoolPolicy.KidsOnly && !KidHold.isMadeForKids(entry, series) -> heldOut++
                policy == PoolPolicy.GrownUp && KidHold.hasChildrensRating(entry, series) -> childrensRatingOut++
                else -> admit(entry, series)
            }
        }

        private fun admit(
            entry: RuleItem,
            series: RuleSeries?,
        ) {
            val item = entry.item
            if (item.kind == ScheduleItemKind.Episode && item.seriesId != null && series == null) unknownSeries++
            val passes =
                if (policy == PoolPolicy.KidsOnly) {
                    KidHold.passesKidCeiling(entry, series, maxRating)
                } else {
                    passesCeiling(entry, series, maxRating)
                }
            if (passes) kept.add(item) else blocked++
        }
    }
}
