// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.upstream.Allocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spike #563: the standby's load control stops loading at the hold while holding, and is the delegate otherwise. */
@UnstableApi
class HoldingLoadControlTest {
    private var holding = true
    private var asked = 0
    private val delegate =
        object : LoadControl {
            override fun getAllocator(playerId: PlayerId): Allocator = PlaybackBuffering.mediaAllocator()

            override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
                asked++
                return true
            }
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

    private companion object {
        const val HOLD_US = 2_000_000L
    }
}
