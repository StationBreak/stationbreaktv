// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.LineupEdits

/**
 * The channel numbers a viewer can pick, for features that show them. They come from the engine's rules, so a screen
 * never keeps its own copy of the bands.
 */
object ChannelNumbers {
    /** A grown-up channel is first offered this number when it has none. */
    private const val FIRST_GROWN_UP = 100

    /** Every number a channel may have. */
    val all: IntRange = LineupEdits.MIN_NUMBER..LineupEdits.MAX_NUMBER

    /** The numbers that belong to kids channels alone. */
    val kids: IntRange = Band.Kids.numbers

    /** Where a number is first offered to a channel that has none: the start of the kids band for a kid channel. */
    fun startFor(kid: Boolean): Int = if (kid) kids.first else FIRST_GROWN_UP
}
