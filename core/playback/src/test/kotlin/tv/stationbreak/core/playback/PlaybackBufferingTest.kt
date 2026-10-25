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
    fun invalidValuesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PlaybackBuffering(bufferForPlaybackMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { PlaybackBuffering(bufferForPlaybackAfterRebufferMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { PlaybackBuffering(bufferForPlaybackMs = 60_000) }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackBuffering(minBufferMs = 60_000, maxBufferMs = 50_000)
        }
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
