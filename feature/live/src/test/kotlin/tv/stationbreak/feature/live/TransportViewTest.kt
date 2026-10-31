// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import org.junit.Assert.assertEquals
import org.junit.Test

/** The "N min behind" the transport strip shows is [TransportView.behindMs] read at the clock's time (spec 20). */
class TransportViewTest {
    private fun view(
        paused: Boolean,
        seeking: Boolean = false,
    ) = TransportView(
        paused = paused,
        seeking = seeking,
        seekable = true,
        visible = true,
        mediaMs = 10 * MIN,
        targetMs = null,
        asOfMs = T0,
        blockStartMs = T0 - 15 * MIN,
        inPointMs = 0,
        cutMs = 60 * MIN,
        driftMs = 0,
    )

    @Test
    fun behindGrowsWhilePausedAndHoldsWhilePlayingBehind() {
        // At T0 live is 15 min into the block and the picture sits at 10 min: 5 min behind.
        val paused = view(paused = true)
        assertEquals(5 * MIN, paused.behindMs(T0))
        assertEquals(10 * MIN, paused.behindMs(T0 + 5 * MIN))
        val playing = view(paused = false)
        assertEquals(5 * MIN, playing.behindMs(T0))
        assertEquals(5 * MIN, playing.behindMs(T0 + 5 * MIN))
    }

    @Test
    fun behindHoldsStillWhileSeekingAndIsNeverNegative() {
        val seeking = view(paused = false, seeking = true)
        assertEquals(10 * MIN, seeking.behindMs(T0 + 5 * MIN))
        assertEquals(0L, view(paused = true).behindMs(T0 - 20 * MIN))
    }

    private companion object {
        const val MIN = 60_000L
        const val T0 = 1_800_000_000_000L
    }
}
