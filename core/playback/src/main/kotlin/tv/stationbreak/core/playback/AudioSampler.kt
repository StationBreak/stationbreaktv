// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

/**
 * Decides when the player reports a closing audio position. Media3 says only once that audio began to advance, so the
 * player takes one more sample at stop; the two samples bracket how far the audio clock ran. Used on the player's
 * looper only.
 */
internal class AudioSampler {
    private var started = false

    /** A new play begins; audio has not advanced yet. */
    fun reset() {
        started = false
    }

    /** Media3 reported that audio began to advance. */
    fun advancing() {
        started = true
    }

    /** True once if audio advanced during this play, so a stop takes one closing sample and a repeat stop none. */
    fun takeClosingSample(): Boolean = started.also { started = false }
}
