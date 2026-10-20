// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.RatingEligibility
import tv.stationbreak.core.rules.SeriesState

/**
 * Whether [entry] passes the rating ceiling [maxRating] (docs/rules-format.md, "Items and the pool", rule 4), where
 * [series] is the entry's series from the snapshot or null when it has none or the snapshot lacks it. A null ceiling
 * passes everything. Shared by `PoolEvaluator` and the leftovers pool so the two cannot drift.
 */
internal fun passesCeiling(
    entry: RuleItem,
    series: RuleSeries?,
    maxRating: String?,
): Boolean =
    when {
        maxRating == null -> {
            true
        }

        entry.item.kind == ScheduleItemKind.Movie -> {
            RatingEligibility.isMovieEligible(entry.officialRating, maxRating)
        }

        else -> {
            val state =
                when {
                    entry.item.seriesId == null -> SeriesState.NoSeries
                    series == null -> SeriesState.Unknown
                    else -> SeriesState.Known(series.officialRating)
                }
            RatingEligibility.isEpisodeEligible(entry.officialRating, state, maxRating)
        }
    }
