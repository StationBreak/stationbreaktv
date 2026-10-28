// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.KidHold
import tv.stationbreak.core.engine.Pool
import tv.stationbreak.core.engine.PoolCounts
import tv.stationbreak.core.engine.passesCeiling
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.RatingLadder

/**
 * The pool of a leftovers channel: every snapshot item whose ID is not in `claimed`, under the rating ceiling exactly
 * as `PoolEvaluator` applies it. The caller decides what is claimed. Items are in snapshot order. Counts: nothing
 * matched by source, `blockedByCeiling` and `episodesWithUnknownSeries` as `PoolEvaluator` counts them,
 * `collectionsSynced` from the snapshot, no unresolved entries. When told the channel is held, an unclaimed item that
 * is not made for kids is dropped and counted in `heldOut`, and the kid rating table replaces the ladder, both as
 * `PoolEvaluator` does for a held channel.
 */
object Leftovers {
    private const val NOT_ON_LADDER = "The rating ceiling is not on the ladder"

    /**
     * Throws [IllegalArgumentException] when [maxRating] is not on the rating ladder, as `PoolEvaluator` does. [held]
     * applies the kid hold (ADR-0019).
     */
    fun pool(
        snapshot: RuleSnapshot,
        claimed: Set<CanonicalId>,
        maxRating: String?,
        held: Boolean,
    ): Pool {
        require(maxRating == null || maxRating in RatingLadder.names) { NOT_ON_LADDER }
        val kept = ArrayList<ScheduleItem>()
        var blocked = 0
        var unknownSeries = 0
        var heldOut = 0
        val items = snapshot.items
        for (index in items.indices) {
            val entry = items[index]
            val item = entry.item
            if (item.id in claimed) continue
            val seriesId = item.seriesId
            val series = if (seriesId == null) null else snapshot.series[seriesId]
            if (held && !KidHold.isMadeForKids(entry, series)) {
                heldOut++
                continue
            }
            if (item.kind == ScheduleItemKind.Episode && seriesId != null && series == null) unknownSeries++
            val passes =
                if (held) KidHold.passesKidCeiling(entry, series, maxRating) else passesCeiling(entry, series, maxRating)
            if (passes) kept.add(item) else blocked++
        }
        return Pool(
            items = kept,
            counts =
                PoolCounts(
                    matchedBySource = emptyList(),
                    excluded = 0,
                    blockedByCeiling = blocked,
                    episodesWithUnknownSeries = unknownSeries,
                    unresolvedSources = emptyList(),
                    unresolvedExclusions = emptyList(),
                    collectionsSynced = snapshot.collectionsSynced,
                    heldOut = heldOut,
                ),
        )
    }
}
