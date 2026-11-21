// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.RatingEligibility
import tv.stationbreak.core.rules.RatingLadder
import tv.stationbreak.core.rules.SeriesState

/**
 * Whether [entry] passes the rating ceiling [maxRating] (docs/rules-format.md, "Items and the pool", rule 4), where
 * [series] is the entry's series from the snapshot or null when it has none or the snapshot lacks it. A null ceiling
 * passes everything. Shared by `PoolEvaluator` and the leftovers pool so the two cannot drift.
 *
 * With [approvedIsUnrated] (`kid-hold-6`, ADR-0028: every app channel but the rules-format reference
 * [PoolPolicy.Open]), `Approved` has no rank either: an item whose effective rating, or whose known series' rating, is
 * `Approved` fails every ceiling, as `NR`, `Passed` and no rating already do.
 */
internal fun passesCeiling(
    entry: RuleItem,
    series: RuleSeries?,
    maxRating: String?,
    approvedIsUnrated: Boolean = false,
): Boolean =
    when {
        maxRating != null && approvedIsUnrated && ratedApproved(entry, series) -> {
            false
        }

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

// Whether the rating rule 4 reads for [entry] (its effective rating, and a known series' non-empty rating) is Approved.
private fun ratedApproved(
    entry: RuleItem,
    series: RuleSeries?,
): Boolean {
    val own = entry.officialRating
    val alone = entry.item.kind == ScheduleItemKind.Movie || entry.item.seriesId == null
    val seriesRating = if (alone) null else series?.officialRating
    val effective = if (own.isNullOrEmpty()) seriesRating else own
    return isApproved(effective) || isApproved(seriesRating)
}

private fun isApproved(rating: String?): Boolean = rating != null && RatingLadder.normalise(rating) == "Approved"
