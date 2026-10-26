// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideMeasurementTest {
    private val lines = mutableListOf<String>()
    private var now = 0L

    private fun measurement() =
        GuideMeasurement.create(
            measure = true,
            openedAtNanos = 0,
            window = null,
            periodNs = 16_666_667L,
            log = { lines += it },
            nanoTime = { now },
        )!!

    @Test
    fun disabledStartsNothingAndLogsNothing() {
        val m = GuideMeasurement.create(false, 0, null, 16_666_667L, { lines += it })
        assertNull(m)
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun noWindowStartsNoThread() {
        assertFalse(measurement().threadStarted)
    }

    @Test
    fun sessionWithoutAGridLogsOpenMsMinusOneOnce() {
        val m = measurement()
        now = 5_000_000_000L
        m.finish(windowMs = -1, rows = 0, pip = false)
        m.finish(windowMs = -1, rows = 0, pip = false)
        assertEquals(1, lines.size)
        assertEquals(
            "guide openMs=-1 windowMs=-1 frames=0 p50Ms=0.0 p90Ms=0.0 p99Ms=0.0 missedVsyncs=0 rows=0 pip=false " +
                "durationMs=5000",
            lines.single(),
        )
    }

    @Test
    fun gridShownCountsOnlyTheFirstCall() {
        val m = measurement()
        now = 800_000_000L
        m.gridShown()
        now = 900_000_000L
        m.gridShown()
        m.finish(windowMs = 12, rows = 6, pip = true)
        assertTrue(lines.single().startsWith("guide openMs=800 windowMs=12 "))
    }
}
