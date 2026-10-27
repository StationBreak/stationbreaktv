// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frames the test hands in, as the window's frame-metrics thread would; [stop] can be made to fail. */
private class FakeFrames(
    private val stopFails: Boolean = false,
) : FrameSource {
    var onFrame: ((Long, Int) -> Unit)? = null
    var stopped = false

    override fun start(onFrame: (totalDurationNs: Long, dropped: Int) -> Unit) {
        this.onFrame = onFrame
    }

    override fun stop() {
        stopped = true
        check(!stopFails) { "the listener was already gone" }
    }
}

class GuideMeasurementTest {
    private val lines = mutableListOf<String>()
    private var now = 0L

    private fun measurement(frames: FrameSource? = null) =
        GuideMeasurement.create(
            measure = true,
            openedAtNanos = 0,
            frames = frames,
            periodNs = 16_666_667L,
            log = { lines += it },
            nanoTime = { now },
        )!!

    @Test
    fun disabledStartsNothingAndLogsNothing() {
        val frames = FakeFrames()
        val m = GuideMeasurement.create(false, 0, frames, 16_666_667L, { lines += it })
        assertNull(m)
        assertNull(frames.onFrame)
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun noWindowStartsNoFrames() {
        assertFalse(measurement().listening)
        assertTrue(measurement(FakeFrames()).listening)
    }

    @Test
    fun sessionWithoutAGridLogsOpenMsMinusOneOnce() {
        val m = measurement()
        now = 5_000_000_000L
        m.finish(windowMs = -1, rows = 0)
        m.finish(windowMs = -1, rows = 0)
        assertEquals(1, lines.size)
        assertEquals(
            "guide openMs=-1 windowMs=-1 frames=0 p50Ms=0.0 p90Ms=0.0 p99Ms=0.0 missedVsyncs=0 droppedFrames=0 " +
                "rows=0 durationMs=5000 extensions=0 extended=0 tickLagMaxMs=0",
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
        m.finish(windowMs = 12, rows = 6)
        assertTrue(lines.single().startsWith("guide openMs=800 windowMs=12 "))
    }

    @Test
    fun droppedFramesAreTheSumTheWindowReports() {
        val frames = FakeFrames()
        val m = measurement(frames)
        val report = checkNotNull(frames.onFrame)
        report(10_000_000L, 0)
        report(40_000_000L, 2)
        report(18_000_000L, 1)
        m.finish(windowMs = 12, rows = 6)
        assertTrue(lines.single(), lines.single().contains(" frames=3 "))
        assertTrue(lines.single(), lines.single().contains(" missedVsyncs=3 droppedFrames=3 "))
    }

    @Test
    fun theLineSaysHowOftenTheGridAskedForMoreAndGotIt() {
        val m = measurement()
        m.finish(windowMs = 12, rows = 6, extensionsAsked = 3, extensionsApplied = 2)
        assertTrue(lines.single(), lines.single().contains(" extensions=3 extended=2 "))
    }

    @Test
    fun theLineSaysHowFarTheShownMinuteFellBehind() {
        val m = measurement()
        m.finish(windowMs = 12, rows = 6, tickLagMaxMs = 60_000)
        assertTrue(lines.single(), lines.single().endsWith(" tickLagMaxMs=60000"))
    }

    @Test
    fun aListenerThatWillNotComeOffStillEndsTheSession() {
        val frames = FakeFrames(stopFails = true)
        val m = measurement(frames)
        m.finish(windowMs = 12, rows = 6)
        assertTrue(frames.stopped)
        assertEquals(1, lines.size)
    }
}
