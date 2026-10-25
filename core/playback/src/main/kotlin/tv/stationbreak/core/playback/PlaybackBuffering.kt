// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl

/**
 * How much media the player buffers before it starts, restarts after a stall, and keeps ahead (issue #392). Only the
 * durations are configured; every other load-control setting stays at Media3's default.
 *
 * Why [bufferForPlaybackMs] is 500: Media3 1.11.1's `DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS` is 1000, and on
 * the reference TVs many flips reported playing about 1000 ms after the first frame (toPlayingMs minus toFirstFrameMs),
 * on direct and transcode routes alike: the player waits for that much media after the first frame. Half the wait
 * brings the start forward by up to 500 ms.
 *
 * The risk: a smaller start buffer leaves less margin, so slow devices or slow routes may stall more often early in a
 * play. A device run judges the change by the same two measures before and after: toPlayingMs minus toFirstFrameMs,
 * and the `SBLIVE stall` lines. If stalls rise, raise [bufferForPlaybackMs] here or supply a larger value from app DI.
 *
 * Defaults and their Media3 1.11.1 constants: [minBufferMs] 50000 (`DEFAULT_MIN_BUFFER_MS`), [maxBufferMs] 50000
 * (`DEFAULT_MAX_BUFFER_MS`), [bufferForPlaybackAfterRebufferMs] 2000 (`DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS`).
 * Media3 uses lower "local playback" values only for file-like URIs; this app streams over HTTP, so they never apply.
 * Media3 requires the start buffers not to exceed [minBufferMs] and [minBufferMs] not to exceed [maxBufferMs].
 */
@UnstableApi
data class PlaybackBuffering(
    val bufferForPlaybackMs: Int = START_BUFFER_MS,
    val bufferForPlaybackAfterRebufferMs: Int = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
    val minBufferMs: Int = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
    val maxBufferMs: Int = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
) {
    init {
        require(bufferForPlaybackMs > 0) { "bufferForPlaybackMs must be positive: $bufferForPlaybackMs" }
        require(bufferForPlaybackAfterRebufferMs > 0) {
            "bufferForPlaybackAfterRebufferMs must be positive: $bufferForPlaybackAfterRebufferMs"
        }
        require(bufferForPlaybackMs <= minBufferMs) { "bufferForPlaybackMs must not exceed minBufferMs" }
        require(bufferForPlaybackAfterRebufferMs <= minBufferMs) {
            "bufferForPlaybackAfterRebufferMs must not exceed minBufferMs"
        }
        require(minBufferMs <= maxBufferMs) { "minBufferMs must not exceed maxBufferMs" }
    }

    /** A Media3 load control with these durations and every other setting at Media3's default. */
    fun toLoadControl(): DefaultLoadControl =
        DefaultLoadControl
            .Builder()
            .setBufferDurationsMs(minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs)
            .build()

    companion object {
        /** Half of Media3's 1000 ms, see the class documentation. */
        const val START_BUFFER_MS = 500
    }
}
