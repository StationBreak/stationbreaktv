// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries

/**
 * Which rating counts for an episode of a known series, and what makes a rating absent (`kid-hold-5`, ADR-0019 addendum
 * 2026-11-13). Both [KidHold] and [AnimatedFamilySignal] read it, so the two cannot drift. Pure.
 */
internal object EpisodeRating {
    /**
     * Whether a rating counts as absent: it is null, or it is empty after trimming spaces (U+0020) from both ends, the
     * same trim as `RatingLadder.normalise`. Any other character, a tab, a no-break space or an ideographic space
     * included, makes the rating present (and, being off the ladder, a rating that fails the kid table). Written without
     * a library notion of whitespace so every port reads it the same way.
     */
    fun isAbsent(rating: String?): Boolean {
        if (rating == null) return true
        for (c in rating) if (c != ' ') return false
        return true
    }

    /**
     * The rating that counts for an episode of a known [series]: the series' when it is present (not absent, see
     * [isAbsent]), otherwise the [entry]'s own.
     */
    fun decisive(
        entry: RuleItem,
        series: RuleSeries,
    ): String? = if (isAbsent(series.officialRating)) entry.officialRating else series.officialRating
}
