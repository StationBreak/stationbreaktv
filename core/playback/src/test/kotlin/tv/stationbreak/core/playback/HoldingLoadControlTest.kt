// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/** Spec 24: the standby's load control stops loading at the hold while holding, and is the delegate otherwise. */
@UnstableApi
class HoldingLoadControlTest {
    private var holding = true
    private var asked = 0
    private val calls = mutableListOf<String>()
    private val delegate =
        object : LoadControl {
            override fun getAllocator(playerId: PlayerId): Allocator = PlaybackBuffering.mediaAllocator()

            override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
                asked++
                return true
            }

            override fun onPrepared(playerId: PlayerId) {
                calls += "onPrepared"
            }

            override fun onTracksSelected(
                parameters: LoadControl.Parameters,
                trackGroups: TrackGroupArray,
                trackSelections: Array<out ExoTrackSelection?>,
            ) {
                calls += "onTracksSelected"
            }

            override fun onStopped(playerId: PlayerId) {
                calls += "onStopped"
            }

            override fun onReleased(playerId: PlayerId) {
                calls += "onReleased"
            }

            override fun getBackBufferDurationUs(playerId: PlayerId): Long = BACK_US.also { calls += "back" }

            override fun retainBackBufferFromKeyframe(id: PlayerId): Boolean = true.also { calls += "retain" }

            override fun shouldStartPlayback(params: LoadControl.Parameters): Boolean = true.also { calls += "start" }

            override fun shouldContinuePreloading(
                playerId: PlayerId,
                timeline: Timeline,
                mediaPeriodId: MediaSource.MediaPeriodId,
                bufferedDurationUs: Long,
            ): Boolean = true.also { calls += "preloading" }
        }
    private val control = HoldingLoadControl(delegate, HOLD_US) { holding }

    private fun buffered(us: Long) =
        LoadControl.Parameters(
            PlayerId.UNSET,
            Timeline.EMPTY,
            LoadControl.EMPTY_MEDIA_PERIOD_ID,
            0L,
            us,
            1f,
            false,
            false,
            0L,
            0L,
        )

    @Test
    fun holdingStopsAtTheHoldAndAsksTheDelegateBelowIt() {
        assertTrue(control.shouldContinueLoading(buffered(HOLD_US - 1)))
        assertFalse(control.shouldContinueLoading(buffered(HOLD_US)))
        assertEquals(1, asked)
    }

    @Test
    fun notHoldingItIsTheDelegate() {
        holding = false
        assertTrue(control.shouldContinueLoading(buffered(HOLD_US * 10)))
        assertEquals(1, asked)
    }

    @Test
    fun everyOtherCallIsForwarded() {
        val id = PlayerId.UNSET
        control.onPrepared(id)
        control.onTracksSelected(buffered(0), TrackGroupArray.EMPTY, emptyArray())
        control.onStopped(id)
        control.onReleased(id)
        assertEquals(BACK_US, control.getBackBufferDurationUs(id))
        assertTrue(control.retainBackBufferFromKeyframe(id))
        assertTrue(control.shouldStartPlayback(buffered(0)))
        assertTrue(control.shouldContinuePreloading(id, Timeline.EMPTY, LoadControl.EMPTY_MEDIA_PERIOD_ID, 0L))
        assertEquals(
            listOf(
                "onPrepared",
                "onTracksSelected",
                "onStopped",
                "onReleased",
                "back",
                "retain",
                "start",
                "preloading",
            ),
            calls,
        )
    }

    @Test
    fun everyLoadControlMethodIsOverridden() {
        val declared =
            HoldingLoadControl::class.java.declaredMethods
                .filterNot { it.isSynthetic || it.isBridge }
                .map { it.name to it.parameterTypes.toList() }
                .toSet()
        val missing =
            LoadControl::class.java.methods
                .filterNot {
                    Modifier.isStatic(it.modifiers) ||
                        it.isAnnotationPresent(DEPRECATED)
                }.map { it.name to it.parameterTypes.toList() }
                .filterNot { it in declared }
        assertTrue("HoldingLoadControl does not override $missing", missing.isEmpty())
    }

    private companion object {
        val DEPRECATED = java.lang.Deprecated::class.java
        const val HOLD_US = 2_000_000L
        const val BACK_US = 7L
    }
}
