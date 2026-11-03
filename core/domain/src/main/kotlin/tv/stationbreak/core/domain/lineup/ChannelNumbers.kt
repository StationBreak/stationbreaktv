// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.LineupDefaults
import tv.stationbreak.core.engine.lineup.LineupEdits

/**
 * The channel numbers a viewer can pick, for features that show them. They come from the engine's rules, so a screen
 * never keeps its own copy of the bands.
 */
object ChannelNumbers {
    /** Every number a channel may have. */
    val all: IntRange = LineupEdits.MIN_NUMBER..LineupEdits.MAX_NUMBER

    /** The numbers that belong to kids channels alone. */
    val kids: IntRange = Band.Kids.numbers

    /** The numbers a grown-up custom channel can have. */
    val custom: IntRange = LineupDefaults.CUSTOM_NUMBERS

    /**
     * Where a number is first offered to a channel that has none: the start of the kids band for a kid channel, else
     * the start of the general band.
     */
    fun startFor(kid: Boolean): Int = if (kid) kids.first else Band.General.numbers.first
}
