// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

/** The lineup's tunable defaults, named once so no other file hard-codes them. */
object LineupDefaults {
    /** A recipe qualifies when its whole pool holds this much airable runtime: 12 hours. */
    const val MIN_AIRABLE_RUNTIME_MS: Long = 43_200_000L

    /**
     * The free on-set: the planner's [PLANNER_PICKS] starters plus the four always-on Odds & Ends channels (33, 66, 99,
     * 199), which cost no seat. It is also the older planner's cap, kept only for `withInferredChoices`, which rebuilds
     * that planner's choices from a stored file; the planner's own picks are capped at [PLANNER_PICKS].
     */
    const val CAP: Int = 52

    /**
     * The most starters the planner picks by identity (ADR-0023, the seat rule): its free picks and the pinned starters
     * inside the top of the one order. A custom channel, an edited starter, an opt-in starter, a pinned starter outside
     * the top and the leftovers channels cost no seat, so the lineup can hold more.
     */
    const val PLANNER_PICKS: Int = 48

    /**
     * The most starters that hold previous rules as keepers (the protected and switched-on ones), and the least room
     * the withholding ones have. It is what `RecipeResolver.STARTER_RULES_BUDGET_BYTES` is sized for, beside the
     * catalog's starters, so the per-copy budget does not move with [CAP].
     */
    const val KEEPER_COPIES: Int = 60

    /** Fewer qualifying recipes than this and the first choice is not finalised yet. */
    const val FINALISE_THRESHOLD: Int = 5

    /** The broadcast day starts at this wall-clock time. */
    const val DAY_START: String = "06:00"

    /** Numbers kept for the user's own channels: spec 12's reserved band, where a new custom channel is numbered. */
    val CUSTOM_NUMBERS: IntRange = 600..899

    /**
     * When the Christmas channels (Snowglobe, Always December) air: late November to the year end, by the viewer's
     * calendar. The seasons are named here once, so a window is one change.
     */
    val CHRISTMAS_SEASON: SeasonWindow = SeasonWindow(startMonth = 11, startDay = 22, endMonth = 12, endDay = 31)

    /** When the Halloween channels (Spooky Sleepover, Jack-o'-Lantern Lounge) air: September 24 to October 31. */
    val HALLOWEEN_SEASON: SeasonWindow = SeasonWindow(startMonth = 9, startDay = 24, endMonth = 10, endDay = 31)
}
