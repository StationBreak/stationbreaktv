// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

/**
 * The pure rating check (docs/rules-format.md, "Maximum rating and the rating ladder"). It fails closed: under a
 * ceiling an item passes only if its rating has a tier on the [RatingLadder] and that tier is at or below the
 * ceiling's. An item with no rating, a blank rating, `NR`, `Unrated`, or any string that is not on the ladder
 * (another country's system, say) is blocked. With no ceiling every item passes. Account limits set on the server can
 * only narrow this, never widen it.
 *
 * There is one function per kind of item, and they are not interchangeable. A movie uses [isMovieEligible]: its own
 * rating decides. An episode uses [isEpisodeEligible], which also checks its series' rating (amended rule 4 of "Items
 * and the pool"). Calling the movie form for an episode skips the series check and can let a stale copy of the series'
 * rating through a ceiling the series now fails.
 */
object RatingEligibility {
    private const val NOT_ON_LADDER = "The rating ceiling is not on the ladder"

    /**
     * For a **movie**, and for the raw ladder check: true if an item rated [rating] (null for none) may air under
     * [maxRating] (null for no ceiling). Do not use it for an episode: use [isEpisodeEligible]. [maxRating] must be a
     * ladder name in its exact spelling: a reader has already rejected a file with anything else, so a different value
     * here is a bug and throws [IllegalArgumentException] instead of being read as "no ceiling".
     */
    fun isMovieEligible(
        rating: String?,
        maxRating: String?,
    ): Boolean {
        val ceiling = maxRating?.let { requireNotNull(RatingLadder.rankOfName(it)) { NOT_ON_LADDER } }
        val rank = RatingLadder.rankOf(rating)
        return ceiling == null || (rank != null && rank <= ceiling)
    }

    /**
     * For an **episode** (docs/rules-format.md, amended rule 4). Under a ceiling the episode passes only if both hold:
     *  - (a) its effective rating passes. That is [ownRating], or the series' rating when [ownRating] is null or the
     *    empty string and [series] is [SeriesState.Known]. Any other own rating is used as it is, even `NR` or a
     *    blank rating, which then has no tier.
     *  - (b) the series' rating passes, when [series] is [SeriesState.Known] and has a rating (anything but null or
     *    the empty string). A series rated `NR`, `Unrated` or blank has no tier and so blocks its episodes. An
     *    episode's own rating is often a copy of its series' rating and whether the copy follows a re-rating is not
     *    verified; (b) keeps the ceiling safe whatever the copy says.
     *
     * A [SeriesState.Unknown] series (its ID is present but no such series is in the snapshot) is blocked under any
     * ceiling. A [SeriesState.NoSeries] episode (no series ID) is checked under (a) alone, with its own rating. With no
     * ceiling ([maxRating] null) every episode passes. [maxRating] is validated as in [isMovieEligible], whatever
     * [series] is. Do not use this for a movie: use [isMovieEligible].
     */
    fun isEpisodeEligible(
        ownRating: String?,
        series: SeriesState,
        maxRating: String?,
    ): Boolean =
        when (series) {
            // No rating at all: blocked under any ceiling, eligible with none (and a bad ceiling still throws).
            SeriesState.Unknown -> {
                isMovieEligible(null, maxRating)
            }

            SeriesState.NoSeries -> {
                isMovieEligible(ownRating, maxRating)
            }

            is SeriesState.Known -> {
                val effective = if (ownRating.isNullOrEmpty()) series.rating else ownRating
                val seriesPasses = series.rating.isNullOrEmpty() || isMovieEligible(series.rating, maxRating)
                isMovieEligible(effective, maxRating) && seriesPasses
            }
        }
}
