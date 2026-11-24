// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.feature.live.TransportView

/** The offset the placeholder strip shows, and when the screen stays awake (spec 20, ADR-0021). */
class BehindMinutesTest {
    private fun view(
        paused: Boolean = false,
        behindMs: Long,
    ) = TransportView(
        paused = paused,
        seeking = false,
        seekable = true,
        visible = true,
        mediaMs = 0,
        targetMs = null,
        asOfMs = NOW,
        blockStartMs = NOW - behindMs,
        inPointMs = 0,
        cutMs = 60 * MIN,
        driftMs = 0,
    )

    @Test
    fun caughtUpShowsNoOffset() = assertNull(behindOffset(view(behindMs = 0), NOW))

    @Test
    fun underASecondShowsNoOffset() = assertNull(behindOffset(view(behindMs = 999), NOW))

    @Test
    fun theOffsetIsInSecondsNotWholeMinutes() {
        assertEquals(OffsetParts(0, 0, 59), behindOffset(view(behindMs = 59_000), NOW))
        assertEquals(OffsetParts(0, 1, 1), behindOffset(view(behindMs = 61_000), NOW))
        assertEquals(OffsetParts(0, 2, 59), behindOffset(view(behindMs = 2 * MIN + 59_000), NOW))
    }

    @Test
    fun pausedOffsetGrowsWithTheClock() {
        val paused = view(paused = true, behindMs = 5 * MIN)
        assertEquals(OffsetParts(0, 5, 0), behindOffset(paused, NOW))
        assertEquals(OffsetParts(0, 8, 0), behindOffset(paused, NOW + 3 * MIN))
    }

    @Test
    fun theScreenStaysOnUnlessPaused() {
        assertTrue(keepsScreenOn(null))
        assertFalse(keepsScreenOn(view(paused = true, behindMs = MIN)))
        assertTrue(keepsScreenOn(view(paused = false, behindMs = MIN)))
    }

    private companion object {
        const val MIN = 60_000L
        const val NOW = 1_800_000_000_000L
    }
}
