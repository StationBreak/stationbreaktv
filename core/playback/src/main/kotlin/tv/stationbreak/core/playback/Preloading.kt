// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.util.Log

/**
 * Spike #563: the switch and the constants of preparing a neighbour channel's media ahead of a flip.
 *
 * The switch is on by default. A device run turns it off for the baseline without a new APK:
 * `adb shell setprop log.tag.SBPRELOAD ASSERT` (back on: `adb shell setprop log.tag.SBPRELOAD INFO`). It is read at
 * each pre-resolve, so it takes effect for the next neighbour prepared.
 */
object PreloadSettings {
    /** The system property tag the switch reads (an Android log tag, 23 characters at most). */
    const val SWITCH_TAG = "SBPRELOAD"

    /**
     * How much media a preload buffers after the index is read and the tracks selected, in ms. 0 is "index only": the
     * source is opened, its index read and its tracks selected, and nothing is buffered. Above 0, that many ms are
     * also loaded from the position the preload was made for.
     */
    const val BUFFER_MS = 0L

    /** A preload older than this (ms) is dropped and made again by the live channel's look-ahead (5 minutes). */
    const val MAX_AGE_MS = 5L * 60L * 1_000L

    /** True unless the switch is turned off. Never throws; where the log service is unavailable it reads as off. */
    fun enabled(): Boolean = runCatching { Log.isLoggable(SWITCH_TAG, Log.WARN) }.getOrDefault(false)
}

/**
 * What the player knows of one preload: [ageMs] since it was asked for, and [setupMs] from the ask to the source being
 * prepared (the index read and tracks selected), or null while it is still working.
 */
data class PreloadState(
    val ageMs: Long,
    val setupMs: Long?,
)

/** What a tune that was handed a preloaded source reports: numbers only. */
data class PreloadUse(
    val preloaded: Boolean,
    val ageMs: Long,
    val setupMs: Long,
)
