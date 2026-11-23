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

/**
 * The lowest band a channel may use, when a number is below what the channel airs allows (#1097): its numbers start at
 * [first]. Never the kids band (a kid channel may use any number from 1). A screen words it from [first], never from a
 * copy of the bands.
 */
enum class BandNeeded(
    val first: Int,
) {
    /** Family stations and up (ceiling at or below PG, and Teen, and Adult). */
    Family(Band.Family.numbers.first),

    /** Teen stations and up. */
    Teen(Band.Teen.numbers.first),

    /** Adult stations, After Hours included: 100 and up. */
    General(Band.General.numbers.first),
    ;

    companion object {
        /** The band the engine says a channel needs ([Band.Kids] never is one). */
        fun of(band: Band): BandNeeded =
            when (band) {
                Band.Family -> Family
                Band.Teen -> Teen
                Band.General, Band.Drama, Band.Genre, Band.World, Band.Premium, Band.Seasonal -> General
                Band.Kids -> error("a channel never needs the kids band: a kid channel may use any number")
            }
    }
}
