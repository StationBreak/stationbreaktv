// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoadCounterTest {
    private val counter = LoadCounter()

    @Test
    fun loadsBeforeTheTracksAreCountedWithTheirTimeSizeAndLongest() {
        counter.reset()
        counter.started()
        counter.finished(durationMs = 40, bytes = 2_048)
        counter.started()
        counter.finished(durationMs = 300, bytes = 1_000_000)
        val stats = counter.close("video/x-matroska")
        assertEquals(LoadStats(loads = 2, loadMs = 340, maxLoadMs = 300, loadKb = 978, container = "x-matroska"), stats)
    }

    @Test
    fun aLoadStillRunningWhenTheTracksAreKnownIsCountedAsStartedOnly() {
        counter.reset()
        counter.started()
        counter.finished(10, 100)
        counter.started()
        val stats = counter.close("video/mp4")
        assertEquals(2, stats?.loads)
        assertEquals(10L, stats?.loadMs)
    }

    @Test
    fun loadsAfterTheTracksAreKnownDoNotChangeTheReportedNumbers() {
        counter.reset()
        counter.started()
        counter.finished(10, 1_024)
        val first = counter.close("video/mp4")
        counter.started()
        counter.finished(500, 9_999)
        assertEquals(first, counter.close("video/mp4"))
        assertEquals(1, first?.loads)
    }

    @Test
    fun resetStartsAFreshPlay() {
        counter.reset()
        counter.started()
        counter.finished(10, 1_024)
        counter.close("video/mp4")
        counter.reset()
        counter.started()
        assertEquals(1, counter.close("video/mp4")?.loads)
    }

    @Test
    fun noLoadsAtAllReportsNothing() {
        counter.reset()
        assertNull(counter.close("video/mp4"))
    }

    @Test
    fun theContainerIsTheMimeSubtypeOrUnknown() {
        assertEquals("x-matroska", containerSubtype("video/x-matroska"))
        assertEquals("mp2t", containerSubtype("video/MP2T"))
        assertEquals("unknown", containerSubtype(null))
        assertEquals("unknown", containerSubtype("video/"))
        // Nothing that is not a plain token reaches the log, whatever the format says.
        assertEquals("unknown", containerSubtype("video/mp4 uri=http://host/x?token=1"))
    }
}
