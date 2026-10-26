// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GuideFramesTest {
    private val ms = 1_000_000L

    @Test
    fun nearestRankPercentiles() {
        val frames = LongArray(100) { (100 - it) * ms } // 1..100 ms, unsorted
        val p = percentiles(frames)
        assertEquals(100, p.frames)
        assertEquals(50 * ms, p.p50Ns)
        assertEquals(90 * ms, p.p90Ns)
        assertEquals(99 * ms, p.p99Ns)
    }

    @Test
    fun emptySessionLogsZeroFrames() {
        val line = GuideReport(-1, -1, percentiles(LongArray(0)), 0, 0, false, 0).line()
        assertEquals(
            "guide openMs=-1 windowMs=-1 frames=0 p50Ms=0.0 p90Ms=0.0 p99Ms=0.0 missedVsyncs=0 rows=0 pip=false " +
                "durationMs=0",
            line,
        )
    }

    @Test
    fun lineHasNumbersAndBooleansOnly() {
        val p = percentiles(longArrayOf(16_700_000L, 33_400_000L, 8_000_000L))
        val line = GuideReport(850, 12, p, 3, 14, true, 9_000).line()
        assertEquals(
            "guide openMs=850 windowMs=12 frames=3 p50Ms=16.7 p90Ms=33.4 p99Ms=33.4 missedVsyncs=3 rows=14 pip=true " +
                "durationMs=9000",
            line,
        )
        val values = line.removePrefix("guide ").split(' ').map { it.substringAfter('=') }
        assertFalse(values.any { v -> v.any { it.isLetter() } && v != "true" && v != "false" })
    }

    @Test
    fun missedVsyncsCountsSkippedPeriods() {
        val period = 16_666_667L
        // Frames at 0, 1, 2, then a gap of 4 periods (3 missed), then 1 period.
        val vsyncs = longArrayOf(0, 1, 2, 6, 7).map { it * period }.toLongArray()
        assertEquals(3, missedVsyncs(vsyncs, period))
        assertEquals(0, missedVsyncs(longArrayOf(5), period))
        assertEquals(0, missedVsyncs(vsyncs, 0))
    }
}
