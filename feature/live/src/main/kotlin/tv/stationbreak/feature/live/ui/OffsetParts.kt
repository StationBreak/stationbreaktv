// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import tv.stationbreak.feature.live.R

private const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

/**
 * An offset from live split into whole [hours], [minutes] and [seconds] (each under its next unit), for the transport
 * strip. Pure, so the split is tested without Android resources; [offsetText] puts the words to it.
 */
internal data class OffsetParts(
    val hours: Int,
    val minutes: Int,
    val seconds: Int,
) {
    companion object {
        /** [offsetMs] rounded to the nearest second, then split; a negative offset counts as 0. */
        fun of(offsetMs: Long): OffsetParts {
            val total = (maxOf(0L, offsetMs) + MS_PER_SECOND / 2) / MS_PER_SECOND
            return OffsetParts(
                hours = (total / SECONDS_PER_HOUR).toInt(),
                minutes = (total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE).toInt(),
                seconds = (total % SECONDS_PER_MINUTE).toInt(),
            )
        }
    }
}

/** "1 hour 2 minutes 10 seconds": the units of [parts] that are not zero, in order, each from its plurals resource. */
@Composable
internal fun offsetText(parts: OffsetParts): String =
    buildList {
        if (parts.hours > 0) add(pluralStringResource(R.plurals.live_offset_hours, parts.hours, parts.hours))
        if (parts.minutes > 0) add(pluralStringResource(R.plurals.live_offset_minutes, parts.minutes, parts.minutes))
        if (parts.seconds > 0) add(pluralStringResource(R.plurals.live_offset_seconds, parts.seconds, parts.seconds))
    }.joinToString(" ")
