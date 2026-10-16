// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

/**
 * The pure rating check (docs/rules-format.md, "Maximum rating and the rating ladder"). It fails closed: under a ceiling an
 * item passes only if its rating has a tier on the [RatingLadder] and that tier is at or below the ceiling's. An item with
 * no rating, a blank rating, `NR`, `Unrated`, or any string that is not on the ladder (another country's system, say) is
 * blocked. With no ceiling every item passes. Account limits set on the server can only narrow this, never widen it.
 */
object RatingEligibility {
    /**
     * True if an item rated [rating] (null for none) may air under [maxRating] (null for no ceiling). [maxRating] must be
     * a ladder name in its exact spelling: a reader has already rejected a file with anything else, so a different value
     * here is a bug and throws [IllegalArgumentException] instead of being read as "no ceiling".
     */
    fun isEligible(
        rating: String?,
        maxRating: String?,
    ): Boolean {
        if (maxRating == null) return true
        val ceiling = requireNotNull(RatingLadder.rankOfName(maxRating)) { "The rating ceiling is not on the ladder" }
        val rank = RatingLadder.rankOf(rating) ?: return false
        return rank <= ceiling
    }

    /**
     * Like [isEligible] for an episode: when it has no rating of its own ([ownRating] is null or the empty string) it uses
     * its series' rating. Any other own rating is used as it is, even `NR` or a blank, which then have no tier.
     */
    fun isEligible(
        ownRating: String?,
        seriesRating: String?,
        maxRating: String?,
    ): Boolean = isEligible(if (ownRating.isNullOrEmpty()) seriesRating else ownRating, maxRating)
}
