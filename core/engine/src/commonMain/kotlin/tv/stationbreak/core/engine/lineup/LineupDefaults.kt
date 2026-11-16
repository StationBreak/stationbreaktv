// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

/** The lineup's tunable defaults, named once so no other file hard-codes them. */
object LineupDefaults {
    /** A recipe qualifies when its whole pool holds this much airable runtime: 12 hours. */
    const val MIN_AIRABLE_RUNTIME_MS: Long = 43_200_000L

    /**
     * The legacy rule's cap: the most channels switched on at once, the leftovers included. It stays for the ranked
     * paths (a catalog that does not select by identity) and the inference of an older file's choices; a catalog that
     * selects by identity caps the planner's own picks at [PLANNER_PICKS] instead.
     */
    const val CAP: Int = 50

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

    /** Numbers kept for later (seasonal channels). */
    val RESERVED_NUMBERS: IntRange = 900..999
}
