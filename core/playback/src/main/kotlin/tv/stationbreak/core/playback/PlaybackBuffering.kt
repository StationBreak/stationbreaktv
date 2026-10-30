// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * How much media the player buffers before it starts, restarts after a stall, and keeps ahead (issue #392). Only the
 * durations and the byte target are configured; every other load-control setting stays at Media3's default.
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
 * Setting the buffer durations also overrides Media3's separate "local playback" values (used for file, content and
 * asset URIs), so those take the same numbers; it makes no difference here, because the app only plays over HTTP.
 * Media3 requires the start buffers not to exceed [minBufferMs] and [minBufferMs] not to exceed [maxBufferMs].
 *
 * Why [targetBufferBytes] is a quarter of the heap: with no byte target, Media3 sizes it from the selected tracks,
 * about 138 MiB for video, audio and text (`DEFAULT_VIDEO_BUFFER_SIZE` + `DEFAULT_AUDIO_BUFFER_SIZE` +
 * `DEFAULT_TEXT_BUFFER_SIZE`), and holds it as byte arrays on the Java heap. The device profile allows media up to
 * 40 Mbit/s, and 50 s of anything above about 23 Mbit/s reaches that target; on a TV whose heap limit is 192 MiB that
 * one buffer is most of the heap (the Firestick 4K ran out of memory five minutes into a play, 2026-10-29). A quarter
 * of the heap (48 MiB there: about 10 s at 40 Mbit/s, the full 50 s up to about 8 Mbit/s) leaves room for the rest of
 * the app and for the HTTP/2 stream window, which lets OkHttp read up to 16 MiB of a paused direct-play response into
 * the heap. It is kept between Media3's own floor (`DEFAULT_MIN_BUFFER_SIZE`) and the target Media3 would pick for
 * video, audio and text (`DEFAULT_MUXED_BUFFER_SIZE`). Loading stops at whichever of [maxBufferMs] and
 * [targetBufferBytes] is reached first.
 *
 * What the cap does not cover. It holds below [minBufferMs] only because `prioritizeTimeOverSizeThresholds` stays at
 * Media3's `false` for network playback: with `true`, Media3 keeps loading past the byte target until [minBufferMs]
 * (the unit test pins this). The load control is asked between loads, so a load already started finishes first: a
 * direct-play read checks back every 1 MiB, but an HLS segment is read to its end, so a transcode can go one segment
 * over (the server's segment length, about 6 s by Jellyfin's default: about 29 MiB at 40 Mbit/s). The allocator also
 * keeps pieces the drained media gave back, up to the target, so the buffer's heap share stays at the target once it
 * has been reached. With the HTTP/2 window, the realistic worst case on a 192 MiB heap is the 48 MiB cap plus one
 * segment plus 16 MiB, about 93 MiB at 40 Mbit/s and much less at common bitrates, against about 154 MiB before.
 */
@UnstableApi
data class PlaybackBuffering(
    val bufferForPlaybackMs: Int = START_BUFFER_MS,
    val bufferForPlaybackAfterRebufferMs: Int = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
    val minBufferMs: Int = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
    val maxBufferMs: Int = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
    val targetBufferBytes: Int = targetBufferBytesFor(Runtime.getRuntime().maxMemory()),
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
        require(targetBufferBytes > 0) { "targetBufferBytes must be positive: $targetBufferBytes" }
    }

    /**
     * A Media3 load control with these durations and byte target, and every other setting at Media3's default. Its
     * buffer comes from [allocator] (Media3's default one unless given), so a caller can read what the buffer holds.
     */
    fun toLoadControl(allocator: DefaultAllocator = mediaAllocator()): DefaultLoadControl =
        DefaultLoadControl
            .Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs)
            .setTargetBufferBytes(targetBufferBytes)
            .build()

    companion object {
        /** Half of Media3's 1000 ms, see the class documentation. */
        const val START_BUFFER_MS = 500

        /** The media buffer may take one part in this many of the heap. */
        private const val HEAP_SHARE_DIVISOR = 4L

        /** An allocator as Media3's load control makes its own: trimmed on reset, in 64 KiB pieces. */
        fun mediaAllocator(): DefaultAllocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

        /** The byte target for a heap limit of [maxHeapBytes] (`Runtime.maxMemory()`), see the class documentation. */
        fun targetBufferBytesFor(maxHeapBytes: Long): Int =
            (maxHeapBytes / HEAP_SHARE_DIVISOR)
                .coerceIn(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_SIZE.toLong(),
                    DefaultLoadControl.DEFAULT_MUXED_BUFFER_SIZE.toLong(),
                ).toInt()
    }
}
