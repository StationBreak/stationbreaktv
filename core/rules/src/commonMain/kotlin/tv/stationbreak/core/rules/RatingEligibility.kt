// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

/**
 * The pure rating check (docs/rules-format.md, "Maximum rating and the rating ladder"). It fails closed: under a
 * ceiling an item passes only if its rating has a tier on the [RatingLadder] and that tier is at or below the
 * ceiling's. An item with no rating, a blank rating, `NR`, `Unrated`, or any string that is not on the ladder
 * (another country's system, say) is blocked. With no ceiling every item passes. Account limits set on the server can
 * only narrow this, never widen it.
 */
object RatingEligibility {
    private const val NOT_ON_LADDER = "The rating ceiling is not on the ladder"

    /**
     * True if an item rated [rating] (null for none) may air under [maxRating] (null for no ceiling). [maxRating]
     * must be a ladder name in its exact spelling: a reader has already rejected a file with anything else, so a
     * different value here is a bug and throws [IllegalArgumentException] instead of being read as "no ceiling".
     */
    fun isEligible(
        rating: String?,
        maxRating: String?,
    ): Boolean {
        val ceiling = maxRating?.let { requireNotNull(RatingLadder.rankOfName(it)) { NOT_ON_LADDER } }
        val rank = RatingLadder.rankOf(rating)
        return ceiling == null || (rank != null && rank <= ceiling)
    }

    /**
     * The check for an episode (docs/rules-format.md, amended rule 4). Under a ceiling the episode passes only if both
     * hold:
     *  - (a) its effective rating passes. That is [ownRating], or the series' rating when [ownRating] is null or the
     *    empty string and [series] is [SeriesRating.Known]. Any other own rating is used as it is, even `NR` or a
     *    blank, which then have no tier.
     *  - (b) the series' rating passes, when [series] is [SeriesRating.Known] and has a rating (anything but null or
     *    the empty string). A series rated `NR`, `Unrated` or blank has no tier and so blocks its episodes. This is
     *    what stops a stale copy of the series' rating on the episode from passing a ceiling the series now fails.
     *
     * A [SeriesRating.Unknown] series (not in the snapshot) is blocked under any ceiling. A [SeriesRating.NoSeries]
     * episode is checked under (a) alone, with its own rating. With no ceiling ([maxRating] null) every episode passes.
     * [maxRating] is validated as for the movie form, whatever [series] is.
     */
    fun isEligible(
        ownRating: String?,
        series: SeriesRating,
        maxRating: String?,
    ): Boolean =
        when (series) {
            // No rating at all: blocked under any ceiling, eligible with none (and a bad ceiling still throws).
            SeriesRating.Unknown -> {
                isEligible(null, maxRating)
            }

            SeriesRating.NoSeries -> {
                isEligible(ownRating, maxRating)
            }

            is SeriesRating.Known -> {
                val effective = if (ownRating.isNullOrEmpty()) series.rating else ownRating
                val seriesPasses = series.rating.isNullOrEmpty() || isEligible(series.rating, maxRating)
                isEligible(effective, maxRating) && seriesPasses
            }
        }
}
