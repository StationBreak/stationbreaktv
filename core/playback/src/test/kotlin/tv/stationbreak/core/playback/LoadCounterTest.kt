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
        counter.started(1)
        counter.finished(1, durationMs = 40, bytes = 2_048)
        counter.started(2)
        counter.finished(2, durationMs = 300, bytes = 1_000_000)
        val stats = counter.close("video/x-matroska")
        assertEquals(LoadStats(loads = 2, loadMs = 340, maxLoadMs = 300, loadKb = 978, container = "x-matroska"), stats)
    }

    @Test
    fun aLoadStillRunningWhenTheTracksAreKnownIsCountedAsStartedOnly() {
        counter.reset()
        counter.started(1)
        counter.finished(1, 10, 100)
        counter.started(2)
        val stats = counter.close("video/mp4")
        assertEquals(2, stats?.loads)
        assertEquals(10L, stats?.loadMs)
    }

    @Test
    fun loadsAfterTheTracksAreKnownDoNotChangeTheReportedNumbers() {
        counter.reset()
        counter.started(1)
        counter.finished(1, 10, 1_024)
        val first = counter.close("video/mp4")
        counter.started(2)
        counter.finished(2, 500, 9_999)
        assertEquals(first, counter.close("video/mp4"))
        assertEquals(1, first?.loads)
    }

    @Test
    fun resetStartsAFreshPlay() {
        counter.reset()
        counter.started(1)
        counter.finished(1, 10, 1_024)
        counter.close("video/mp4")
        counter.reset()
        counter.started(2)
        assertEquals(1, counter.close("video/mp4")?.loads)
    }

    @Test
    fun noLoadsAtAllReportsNothing() {
        counter.reset()
        assertNull(counter.close("video/mp4"))
    }

    @Test
    fun aFinishWithNoStartSinceResetAddsNothing() {
        counter.reset()
        // The previous play's load, cancelled after this play began.
        counter.finished(7, durationMs = 900, bytes = 5_000_000)
        counter.started(8)
        counter.finished(8, 20, 1_024)
        assertEquals(LoadStats(1, 20, 20, 1, "mp4"), counter.close("video/mp4"))
    }

    @Test
    fun aFinishOfALoadStartedBeforeTheResetAddsNothing() {
        counter.reset()
        counter.started(7)
        counter.reset()
        counter.started(8)
        counter.finished(7, 900, 5_000_000)
        counter.finished(8, 20, 1_024)
        assertEquals(LoadStats(1, 20, 20, 1, "mp4"), counter.close("video/mp4"))
    }

    @Test
    fun aStartOfAnotherItemIsIgnored() {
        counter.reset()
        counter.started(1, forThisPlay = false)
        counter.finished(1, 900, 5_000_000)
        assertNull(counter.close("video/mp4"))
    }

    @Test
    fun aRetryOfTheSameLoadCountsAsAnotherStart() {
        counter.reset()
        counter.started(1)
        counter.finished(1, 30, 0)
        counter.started(1)
        counter.finished(1, 40, 2_048)
        assertEquals(LoadStats(2, 70, 40, 2, "mp4"), counter.close("video/mp4"))
    }

    @Test
    fun aFailedListenerDropsThePlaysNumbers() {
        counter.reset()
        counter.started(1)
        counter.fail()
        assertNull(counter.close("video/mp4"))
        counter.reset()
        counter.started(2)
        assertEquals(1, counter.close("video/mp4")?.loads)
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
