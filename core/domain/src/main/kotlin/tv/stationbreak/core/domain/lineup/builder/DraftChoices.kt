// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.engine.KidHold
import tv.stationbreak.core.rules.RatingLadder

/** What the builder offers to choose from, taken from the lineup and the rating ladder so the two cannot disagree. */
object DraftChoices {
    /**
     * The rating ceilings a channel can have, lowest first. A grown-up channel's are one name per tier of the rating
     * ladder; a kids channel's are the kids hold's own, which stop at PG (the most a kids channel airs), so each means
     * what the hold does with it. "No ceiling" is not in the list; a screen offers it above a grown-up's top.
     */
    fun ratingCeilings(kids: Boolean): List<String> {
        if (kids) return KidHold.CEILING_CHOICES
        return RatingLadder.tiers.map { it.first() }
    }

    /** The kids choice that holds a channel to what [rating] held it to, never looser (see `KidHold.kidsChoiceFor`). */
    fun kidsCeilingFor(rating: String?): String = KidHold.kidsChoiceFor(rating)

    /**
     * The place in [ratingCeilings] of a stored ceiling, by tier (the hold's kid tier for a kids channel, the ladder's
     * otherwise), so a spelling the list lacks is still placed; null when it is none or not on the ladder.
     */
    fun ratingTier(
        rating: String?,
        kids: Boolean,
    ): Int? {
        if (!kids) return RatingLadder.rankOf(rating)
        return KidHold.kidRank(rating)?.coerceAtMost(KidHold.CEILING_CHOICES.lastIndex)
    }
}
