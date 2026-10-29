// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.upstream.DefaultAllocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class PlaybackBufferingTest {
    @Test
    fun defaultsStartAtHalfMedia3sBufferAndKeepEveryOtherMedia3Default() {
        val defaults = PlaybackBuffering()
        assertEquals(500, defaults.bufferForPlaybackMs)
        assertEquals(
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            defaults.bufferForPlaybackAfterRebufferMs,
        )
        assertEquals(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS, defaults.minBufferMs)
        assertEquals(DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, defaults.maxBufferMs)
    }

    @Test
    fun theLoadControlStartsAtTheConfiguredBufferAndRestartsAtTheRebufferOne() {
        val control =
            PlaybackBuffering(bufferForPlaybackMs = 500, bufferForPlaybackAfterRebufferMs = 1_500).prepared()
        assertFalse(control.shouldStartPlayback(parameters(bufferedMs = 499, rebuffering = false)))
        assertTrue(control.shouldStartPlayback(parameters(bufferedMs = 500, rebuffering = false)))
        assertFalse(control.shouldStartPlayback(parameters(bufferedMs = 1_499, rebuffering = true)))
        assertTrue(control.shouldStartPlayback(parameters(bufferedMs = 1_500, rebuffering = true)))
    }

    @Test
    fun aLargerConfiguredStartBufferIsHonoured() {
        val control = PlaybackBuffering(bufferForPlaybackMs = 1_000).prepared()
        assertFalse(control.shouldStartPlayback(parameters(bufferedMs = 999, rebuffering = false)))
        assertTrue(control.shouldStartPlayback(parameters(bufferedMs = 1_000, rebuffering = false)))
    }

    @Test
    fun theTargetBufferIsAQuarterOfTheHeapWithinMedia3sBounds() {
        val mib = 1_024L * 1_024L
        // The Firestick 4K's growth limit (the OOM of 2026-10-29): 48 MiB, not Media3's ~138 MiB for video and audio.
        assertEquals(48 * mib, PlaybackBuffering.targetBufferBytesFor(192 * mib).toLong())
        assertEquals(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_SIZE,
            PlaybackBuffering.targetBufferBytesFor(32 * mib),
        )
        assertEquals(
            DefaultLoadControl.DEFAULT_MUXED_BUFFER_SIZE,
            PlaybackBuffering.targetBufferBytesFor(4_096 * mib),
        )
        // The default is wired to this process's own heap limit: on the test JVM that is usually clamped to the
        // ceiling, so this pins the wiring, not a number.
        assertEquals(
            PlaybackBuffering.targetBufferBytesFor(Runtime.getRuntime().maxMemory()),
            PlaybackBuffering().targetBufferBytes,
        )
    }

    @Test
    fun loadingStopsAtTheTargetBufferEvenBelowTheMinimumDuration() {
        val segment = C.DEFAULT_BUFFER_SEGMENT_SIZE
        val control = PlaybackBuffering(targetBufferBytes = 4 * segment).prepared()
        val allocator = control.getAllocator(PlayerId.UNSET)
        repeat(3) { allocator.allocate() }
        // 10 s buffered is far below the 50 s minimum: only the byte target can stop the load, and only while
        // prioritizeTimeOverSizeThresholds stays false (with true, Media3 would keep loading to the minimum).
        assertTrue(control.shouldContinueLoading(parameters(bufferedMs = 10_000, rebuffering = false)))
        allocator.allocate()
        assertFalse(control.shouldContinueLoading(parameters(bufferedMs = 10_000, rebuffering = false)))
    }

    @Test
    fun theBufferComesFromTheGivenAllocatorSoItsSizeCanBeRead() {
        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val control = PlaybackBuffering().toLoadControl(allocator).also { it.onPrepared(PlayerId.UNSET) }
        repeat(2) { control.getAllocator(PlayerId.UNSET).allocate() }
        assertEquals(2 * C.DEFAULT_BUFFER_SEGMENT_SIZE, allocator.totalBytesAllocated)
    }

    @Test
    fun invalidValuesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PlaybackBuffering(bufferForPlaybackMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { PlaybackBuffering(bufferForPlaybackAfterRebufferMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { PlaybackBuffering(bufferForPlaybackMs = 60_000) }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackBuffering(minBufferMs = 60_000, maxBufferMs = 50_000)
        }
        assertThrows(IllegalArgumentException::class.java) { PlaybackBuffering(targetBufferBytes = 0) }
    }

    private fun PlaybackBuffering.prepared() = toLoadControl().also { it.onPrepared(PlayerId.UNSET) }

    private val timeline: Timeline = SinglePeriodTimeline(0L, true, false, false, null, MediaItem.EMPTY)

    private fun parameters(
        bufferedMs: Long,
        rebuffering: Boolean,
    ) = LoadControl.Parameters(
        PlayerId.UNSET,
        timeline,
        MediaPeriodId(timeline.getUidOfPeriod(0)),
        C.TIME_UNSET,
        bufferedMs * 1_000,
        1f,
        true,
        rebuffering,
        C.TIME_UNSET,
        C.TIME_UNSET,
    )
}
