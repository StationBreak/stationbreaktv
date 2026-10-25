// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

@UnstableApi
class LoadCounterTest {
    private val counter = LoadCounter()

    @Test
    fun opensBeforeTheTracksAreCountedWithTheirTimeBytesLongestAndFurthestJump() {
        counter.reset(1)
        counter.opened(1, position = 0, durationMs = 40)
        counter.read(1, 2_048)
        counter.opened(1, position = 5_000_000, durationMs = 300)
        counter.read(1, 1_000_000)
        counter.opened(1, position = 1_024, durationMs = 20)
        val stats = counter.close("video/x-matroska")
        assertEquals(
            LoadStats(3, 360, 300, 978, "x-matroska", seekKb = 4_882),
            stats,
        )
    }

    @Test
    fun aSingleOpenHasNoSeek() {
        counter.reset(1)
        counter.opened(1, 4_096, 10)
        assertEquals(0L, counter.close("video/mp4")?.seekKb)
    }

    @Test
    fun opensAndReadsAfterTheTracksAreKnownDoNotChangeTheNumbers() {
        counter.reset(1)
        counter.opened(1, 0, 10)
        counter.read(1, 1_024)
        val first = counter.close("video/mp4")
        counter.opened(1, 9_999_999, 500)
        counter.read(1, 9_999)
        assertEquals(first, counter.close("video/mp4"))
        assertEquals(1, first?.loads)
    }

    @Test
    fun aLateOpenOrReadOfThePreviousPlayIsIgnored() {
        counter.reset(1)
        counter.reset(2)
        counter.opened(1, 8_000_000, 900)
        counter.read(1, 5_000_000)
        counter.opened(2, 0, 20)
        counter.read(2, 1_024)
        assertEquals(LoadStats(1, 20, 20, 1, "mp4", 0), counter.close("video/mp4"))
    }

    @Test
    fun resetStartsAFreshPlay() {
        counter.reset(1)
        counter.opened(1, 0, 10)
        counter.close("video/mp4")
        counter.reset(2)
        counter.opened(2, 0, 10)
        assertEquals(1, counter.close("video/mp4")?.loads)
    }

    @Test
    fun noOpensAtAllReportsNothing() {
        counter.reset(1)
        assertNull(counter.close("video/mp4"))
    }

    @Test
    fun aFailedCountDropsThePlaysNumbers() {
        counter.reset(1)
        counter.opened(1, 0, 10)
        counter.fail()
        assertNull(counter.close("video/mp4"))
        counter.reset(2)
        counter.opened(2, 0, 10)
        assertEquals(1, counter.close("video/mp4")?.loads)
    }

    @Test
    fun aDataSourceReportsItsOpensAndReadsUnderTheGenerationItWasMadeIn() {
        counter.reset(1)
        val factory = DataSource.Factory { FakeSource() }.counting(counter)
        val old = factory.createDataSource() as CountingDataSource
        counter.reset(2)
        val fresh = factory.createDataSource() as CountingDataSource
        old.timedOpen(8_192) { 0 }
        old.read(ByteArray(16), 0, 16)
        fresh.timedOpen(0) { 0 }
        fresh.read(ByteArray(2_048), 0, 2_048)
        val stats = counter.close("video/mp4")
        assertEquals(1, stats?.loads)
        assertEquals(2L, stats?.loadKb)
        assertEquals(0L, stats?.seekKb)
    }

    @Test
    fun aFaultInTheCountingNeverReachesTheReaderAndDropsTheNumbers() {
        val throwing =
            object : LoadCounter() {
                override fun read(
                    generation: Long,
                    count: Long,
                ): Unit = throw IllegalStateException("counting fault")
            }
        throwing.reset(1)
        throwing.opened(1, 0, 10)
        assertEquals(5, CountingDataSource(FakeSource(5), throwing, 1).read(ByteArray(8), 0, 8))
        assertNull(throwing.close("video/mp4"))
    }

    @Test
    fun aFailingOpenIsStillCountedAndItsExceptionPasses() {
        counter.reset(1)
        val source = CountingDataSource(FakeSource(), counter, 1)
        assertThrows(IOException::class.java) { source.timedOpen(0) { throw IOException("boom") } }
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

@UnstableApi
private class FakeSource(
    private val chunk: Int = -1,
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long = -1

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = if (chunk >= 0) chunk else length

    override fun getUri(): Uri? = null

    override fun close() = Unit
}
