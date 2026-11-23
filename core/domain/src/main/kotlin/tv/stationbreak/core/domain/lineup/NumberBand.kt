// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.Band

/**
 * The number bands as a viewer names them (#1100): Family Mode's three young bands, then everything else with a number.
 * The ranges are the engine's [Band] ranges, so a screen keeps no copy of 33, 66 or 99. The product names map to the
 * code names, which stay as they are, with their stored values:
 *
 * - [Tots] is [Band.Kids] (1 to 33)
 * - [Kids] is [Band.Family] (34 to 66)
 * - [Teens] is [Band.Teen] (67 to 99)
 * - [GrownUps] is every number from [Band.General] up, so the general, drama, genre, world, premium, seasonal and any
 *   custom number (100 and up). "Grown-ups" is a proposed label, one string in each feature.
 *
 * Use [bandOf] to classify a number.
 */
enum class NumberBand(
    val numbers: IntRange,
) {
    Tots(Band.Kids.numbers),
    Kids(Band.Family.numbers),
    Teens(Band.Teen.numbers),
    GrownUps(Band.General.numbers.first..Int.MAX_VALUE),
    ;

    /** The band's last number, or null for the open-ended one ([GrownUps]: 100 and up). */
    val last: Int? get() = numbers.last.takeIf { it != Int.MAX_VALUE }
}

/** The band [number] is in, or null when it has none: no number, or one below 1. */
fun bandOf(number: Int?): NumberBand? = number?.let { n -> NumberBand.entries.firstOrNull { n in it.numbers } }
