// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.domain.lineup.FamilyTier
import tv.stationbreak.core.engine.KidHold
import tv.stationbreak.core.rules.RatingLadder

/** What the builder offers to choose from, taken from the lineup and the rating ladder so the two cannot disagree. */
object DraftChoices {
    // The highest ladder name a Teens channel may have (PG-13 / TV-14 tier, the Teen band's limit).
    private const val TEENS_LIMIT = "TV-14"

    /**
     * The rating ceilings a channel can have, lowest first. A grown-up channel's are one name per tier of the rating
     * ladder; a Tots or Kids channel's are the kids hold's own, which stop at PG (under `kid-hold-6` a kids channel
     * airs at most TV-Y7, so PG airs what TV-Y7 airs; ADR-0028); a Teens channel's are the ladder's up to TV-14.
     * "No ceiling" is not in the list; a screen offers it above a grown-up's top.
     */
    fun ratingCeilings(tier: FamilyTier?): List<String> =
        when {
            tier == null -> RatingLadder.tiers.map { it.first() }
            tier.keepsKidCeilings -> KidHold.CEILING_CHOICES
            else -> RatingLadder.tiers.take(checkNotNull(RatingLadder.rankOf(TEENS_LIMIT)) + 1).map { it.first() }
        }

    /**
     * Whether [rating] is a ceiling [tier] may keep as it is: one of the kids hold's own for a Tots or Kids channel, a
     * ladder name at or below TV-14 (spelled as the list or not, PG is) for a Teens one. No ceiling never is.
     */
    fun allows(
        rating: String?,
        tier: FamilyTier,
    ): Boolean =
        if (tier.keepsKidCeilings) {
            rating in KidHold.CEILING_CHOICES
        } else {
            RatingLadder.rankOf(rating)?.let { it <= checkNotNull(RatingLadder.rankOf(TEENS_LIMIT)) } == true
        }

    /**
     * The choice of [tier]'s list that holds a channel to what [rating] held it to, never looser: the kids choice for
     * a Tots or Kids channel (see `KidHold.kidsChoiceFor`), the ladder's own name capped at TV-14 for a Teens one.
     */
    fun ceilingFor(
        rating: String?,
        tier: FamilyTier,
    ): String = if (tier.keepsKidCeilings) KidHold.kidsChoiceFor(rating) else ratingCeilings(tier).last()

    /**
     * The place in [ratingCeilings] of a stored ceiling, by tier (the hold's kid tier for a Tots or Kids channel, the
     * ladder's otherwise), so a spelling the list lacks is still placed; null when it is none or not on the ladder.
     */
    fun ratingTier(
        rating: String?,
        tier: FamilyTier?,
    ): Int? {
        if (tier?.keepsKidCeilings != true) return RatingLadder.rankOf(rating)
        return KidHold.kidRank(rating)?.coerceAtMost(KidHold.CEILING_CHOICES.lastIndex)
    }
}
