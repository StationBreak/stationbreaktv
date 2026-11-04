// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

/** The curation's tunables: safe defaults in one place. Later curation tasks read them. */
internal object CurationDefaults {
    /** The least a channel must air in a day to be worth a number: 12 hours. */
    const val MIN_USABLE_MS: Long = 43_200_000L

    /** The fewest titles a grown-up channel needs. */
    const val MIN_TITLES: Int = 6

    /** The fewest titles a kid channel needs. */
    const val MIN_TITLES_KIDS: Int = 4

    /** The floor of the share cap: one hour. */
    const val SHARE_FLOOR_MS: Long = 3_600_000L

    /** Guests may take GUEST_NUM / GUEST_DEN of the airtime (3 / 7, at most 30 %). */
    const val GUEST_NUM: Int = 3
    const val GUEST_DEN: Int = 7

    /** The most guest spots one title gets. */
    const val MAX_GUESTS: Int = 2

    /** A guest's tone counts for a fourth. */
    const val GUEST_TONE_DIVISOR: Int = 4

    /** The selection rounds. */
    const val ROUNDS: Int = 6

    /** The most hours a channel's score counts: 80 hours, in ms. */
    const val SCORE_HOURS_CAP_MS: Long = 288_000_000L

    /** A channel's score: 1500 an anchor up to 4 anchors, and 100 a title up to 30 titles. */
    const val SCORE_ANCHOR_POINTS: Long = 1_500L
    const val SCORE_ANCHOR_CAP: Int = 4
    const val SCORE_TITLE_POINTS: Long = 100L
    const val SCORE_TITLE_CAP: Int = 30

    /** The weight of an anchor title, a home title and a guest title. */
    const val WEIGHT_ANCHOR: Int = 3
    const val WEIGHT_HOME: Int = 2
    const val WEIGHT_GUEST: Int = 1

    /** The one share cap: a quarter of [totalMs], at least [SHARE_FLOOR_MS]. */
    fun shareCapMs(totalMs: Long): Long = maxOf(totalMs / SHARE_DIVISOR, SHARE_FLOOR_MS)

    private const val SHARE_DIVISOR = 4
}
