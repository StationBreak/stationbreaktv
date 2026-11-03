// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.domain.lineup.Ceilings
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.LineupDefaults
import tv.stationbreak.core.rules.RatingLadder

/** What the builder offers to choose from, taken from the lineup and the rating ladder so the two cannot disagree. */
object DraftChoices {
    /** The numbers of a grown-up custom channel. */
    val grownUpNumbers: IntRange = LineupDefaults.CUSTOM_NUMBERS

    /** The numbers of a kids channel. */
    val kidsNumbers: IntRange = Band.Kids.numbers

    /**
     * The rating ceilings a channel can have, lowest first, one name per tier of the ladder. A kids channel's list
     * stops at the tier of the kids hold's own ceiling: it never airs above it, so a higher choice would mean nothing.
     * "No ceiling" is not in the list; a screen offers it above the top.
     */
    fun ratingCeilings(kids: Boolean): List<String> {
        val kidsTop = checkNotNull(RatingLadder.rankOf(Ceilings.KIDS_MAX_RATING))
        val top = if (kids) kidsTop else RatingLadder.tiers.lastIndex
        return RatingLadder.tiers.take(top + 1).map { it.first() }
    }

    /** The tier (0 is the lowest) of a stored ceiling, or null when it is none or not on the ladder. */
    fun ratingTier(rating: String?): Int? = RatingLadder.rankOf(rating)
}

/** True when the draft draws on something: a source, or a year range a copy keeps (which is a source too). */
fun ChannelDraft.hasPool(): Boolean = sources.isNotEmpty() || kept?.items.orEmpty().any { it is KeptItem.YearsSource }
