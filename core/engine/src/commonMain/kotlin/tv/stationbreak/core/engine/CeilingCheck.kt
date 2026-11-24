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
 *
 * With [approvedIsUnrated] the rating is read as the tier holds read it ([RatingMap.levelOf], `grown-up-hold-11`,
 * #1124): a country rating such as `DE-12` or `AU-PG` passes a ceiling its mapped level is within, and an unknown or
 * unrated one fails every ceiling (fail closed). The ceiling itself is a ladder name, and a US rating has the same
 * level on the map as its tier on the ladder. Without it ([PoolPolicy.Open], the rules-format reference) only the US
 * ladder reads, as `RatingEligibility` does.
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

        approvedIsUnrated -> {
            passesMappedCeiling(entry, series, maxRating)
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

// Rule 4 (as RatingEligibility reads it) on the mapped level of each rating: a movie or an episode with no series by
// its own rating; an episode of a known series by its effective rating and, when the series has one, the series'
// rating; an episode whose series the snapshot lacks fails.
private fun passesMappedCeiling(
    entry: RuleItem,
    series: RuleSeries?,
    maxRating: String,
): Boolean {
    require(maxRating in RatingLadder.names) { "The rating ceiling is not on the ladder" }
    val ceiling = checkNotNull(RatingLadder.rankOf(maxRating))

    fun within(rating: String?): Boolean = RatingMap.levelOf(rating)?.let { it <= ceiling } == true

    val own = entry.officialRating
    return when {
        entry.item.kind == ScheduleItemKind.Movie || entry.item.seriesId == null -> {
            within(own)
        }

        series == null -> {
            false
        }

        else -> {
            val shows = series.officialRating
            within(if (own.isNullOrEmpty()) shows else own) && (shows.isNullOrEmpty() || within(shows))
        }
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
