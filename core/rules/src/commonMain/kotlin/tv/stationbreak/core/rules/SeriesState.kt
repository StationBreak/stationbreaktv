// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

/**
 * What is known of an episode's series, for [RatingEligibility] (docs/rules-format.md, rule 4). It is a type, not a
 * nullable string, so that "the series is not in the snapshot" cannot be mistaken for "the series has no rating":
 * the first fails closed under a ceiling, the second leaves only the episode's own rating to check.
 */
sealed interface SeriesState {
    /** The series is in the snapshot. [rating] is null or the empty string when the series has no rating. */
    data class Known(
        val rating: String?,
    ) : SeriesState

    /** The episode names a series that is not in the snapshot. It is blocked under any ceiling. */
    data object Unknown : SeriesState

    /** The episode has no series at all (a loose episode). Only its own rating is checked. */
    data object NoSeries : SeriesState
}
