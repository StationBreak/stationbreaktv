// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

/** The lineup's tunable defaults, named once so no other file hard-codes them. */
object LineupDefaults {
    /** A recipe qualifies when its whole pool holds this much airable runtime: 12 hours. */
    const val MIN_AIRABLE_RUNTIME_MS: Long = 43_200_000L

    /** The most channels switched on at once; the leftovers channels count. */
    const val CAP: Int = 50

    /** Fewer qualifying recipes than this and the first choice is not finalised yet. */
    const val FINALISE_THRESHOLD: Int = 5

    /** The broadcast day starts at this wall-clock time. */
    const val DAY_START: String = "06:00"

    /** Numbers kept for the user's own channels: spec 12's reserved band, where a new custom channel is numbered. */
    val CUSTOM_NUMBERS: IntRange = 600..899

    /** Numbers kept for later (seasonal channels). */
    val RESERVED_NUMBERS: IntRange = 900..999
}
