// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import java.util.TimeZone

private const val MILLIS_PER_MINUTE = 60_000
private const val QUARTER_HOUR = 15

/**
 * The device zone's standard offset (summer time not included) in minutes, rounded down to a multiple of 15, which is
 * what the rules format allows (rules-format, "Which offset to write"). It is read once, at a lineup's first setup.
 * [rawOffsetMs] is `TimeZone.getRawOffset()`.
 */
internal fun standardOffsetMinutes(rawOffsetMs: Int = TimeZone.getDefault().rawOffset): Int =
    Math.floorDiv(rawOffsetMs / MILLIS_PER_MINUTE, QUARTER_HOUR) * QUARTER_HOUR
