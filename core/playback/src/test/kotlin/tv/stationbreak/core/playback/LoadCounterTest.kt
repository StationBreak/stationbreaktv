// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

@UnstableApi
class LoadCounterTest {
    private val counter = LoadCounter()

    @Test
    fun opensBeforeTheTracksAreCountedWithTheirTimeBytesLongestAndStartPositions() {
        counter.reset(1)
        counter.opened(1, position = 0, durationMs = 40)
        counter.read(1, 2_048)
        counter.opened(1, position = 5_000_000, durationMs = 300)
        counter.read(1, 1_000_000)
        counter.opened(1, position = 1_024, durationMs = 20)
        val stats = counter.close("video/x-matroska")
        assertEquals(LoadStats(3, 360, 300, 978, "x-matroska", opensKb = "0,4882,1", ioMs = 360), stats)
    }

    @Test
    fun theOpenListIsCappedWithAPlusAndAnExactlyFullListHasNone() {
        counter.reset(1)
        repeat(MAX_OPENS_LISTED) { counter.opened(1, it * 1_024L, 1) }
        assertEquals("0,1,2,3,4,5,6,7", counter.close("video/mp4")?.opensKb)
        counter.reset(2)
        repeat(MAX_OPENS_LISTED + 3) { counter.opened(2, it * 1_024L, 1) }
        val stats = counter.close("video/mp4")
        assertEquals("0,1,2,3,4,5,6,7+", stats?.opensKb)
        assertEquals(MAX_OPENS_LISTED + 3, stats?.loads)
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
        assertEquals(LoadStats(1, 20, 20, 1, "mp4", "0", ioMs = 20), counter.close("video/mp4"))
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
    fun theGenerationOfAMediaItemIsItsMediaIdAndAnUnparsableIdMatchesNoPlay() {
        assertEquals(42L, generationOf("42"))
        assertEquals(-1L, generationOf(""))
        assertEquals(-1L, generationOf("not-a-number"))
        // A source made for an item without a play id never counts, whatever the current play is.
        counter.reset(0)
        counter.opened(generationOf("x"), 0, 5)
        assertNull(counter.close("video/mp4"))
    }

    @Test
    fun aFactoryMakesDataSourcesThatReportUnderTheGenerationItWasGiven() {
        counter.reset(2)
        val old = DataSource.Factory { FakeSource() }.counting(counter, 1).createDataSource() as CountingDataSource
        val fresh = DataSource.Factory { FakeSource() }.counting(counter, 2).createDataSource() as CountingDataSource
        old.timedOpen(8_192, false) { 0 }
        old.read(ByteArray(16), 0, 16)
        fresh.timedOpen(0, false) { 0 }
        fresh.read(ByteArray(2_048), 0, 2_048)
        val stats = counter.close("video/mp4")
        assertEquals(1, stats?.loads)
        assertEquals(2L, stats?.loadKb)
        assertEquals("0", stats?.opensKb)
    }

    @Test
    fun aSidecarOpenAndTheReadsAfterItAreNotCounted() {
        counter.reset(1)
        val source = CountingDataSource(FakeSource(), counter, 1)
        source.timedOpen(0, sidecar = true) { 0 }
        source.read(ByteArray(8), 0, 8)
        source.timedOpen(4_096, sidecar = false) { 0 }
        source.read(ByteArray(1_024), 0, 1_024)
        val stats = counter.close("video/mp4")
        assertEquals(1, stats?.loads)
        assertEquals(1L, stats?.loadKb)
        assertEquals("4", stats?.opensKb)
    }

    @Test
    fun ioTimeSumsTheOpensAndReadsOfSeveralOpens() {
        counter.reset(1)
        val clock = StepClock()
        val source = CountingDataSource(FakeSource { clock.advance(3 * MS) }, counter, 1, clock = clock)
        source.timedOpen(0, false) {
            clock.advance(40 * MS)
            0
        }
        clock.advance(500 * MS) // time outside the data source is not I/O
        source.read(ByteArray(8), 0, 8)
        source.timedOpen(4_096, false) {
            clock.advance(10 * MS)
            0
        }
        source.read(ByteArray(8), 0, 8)
        assertEquals(40L + 10 + 2 * 3, counter.close("video/mp4")?.ioMs)
    }

    @Test
    fun readsAfterTheTracksAreKnownAreNotIoTime() {
        counter.reset(1)
        val clock = StepClock()
        val source = CountingDataSource(FakeSource { clock.advance(300 * MS) }, counter, 1, clock = clock)
        source.timedOpen(0, false) {
            clock.advance(5 * MS)
            0
        }
        val first = counter.close("video/mp4")
        source.read(ByteArray(8), 0, 8)
        source.timedOpen(9, false) {
            clock.advance(900 * MS)
            0
        }
        assertEquals(first, counter.close("video/mp4"))
        assertEquals(5L, first?.ioMs)
    }

    @Test
    fun aSidecarOpenAndItsReadsAreNotIoTime() {
        counter.reset(1)
        val clock = StepClock()
        val source = CountingDataSource(FakeSource { clock.advance(300 * MS) }, counter, 1, clock = clock)
        source.timedOpen(0, sidecar = true) {
            clock.advance(700 * MS)
            0
        }
        source.read(ByteArray(8), 0, 8)
        source.timedOpen(0, sidecar = false) {
            clock.advance(20 * MS)
            0
        }
        assertEquals(20L, counter.close("video/mp4")?.ioMs)
    }

    @Test
    fun theDelegatesExceptionsPassThroughUnchangedAndAFailedOpenIsCounted() {
        counter.reset(1)
        val failure = IOException("boom")
        val source = CountingDataSource(FakeSource(readFailure = failure), counter, 1)
        assertSame(failure, assertThrows(IOException::class.java) { source.timedOpen(0, false) { throw failure } })
        assertSame(failure, assertThrows(IOException::class.java) { source.read(ByteArray(1), 0, 1) })
        assertEquals(1, counter.close("video/mp4")?.loads)
    }

    @Test
    fun theOtherCallsPassThroughToTheDelegate() {
        val delegate = FakeSource()
        val source = CountingDataSource(delegate, counter, 1)
        val listener = NoListener
        source.addTransferListener(listener)
        assertSame(listener, delegate.listener)
        assertSame(delegate.headers, source.responseHeaders)
        assertNull(source.uri)
        source.close()
        assertEquals(1, delegate.closes)
    }

    @Test
    fun aFaultInTheCountingNeverReachesThePlayAndDropsTheNumbers() {
        val sink = ThrowingSink()
        val source = CountingDataSource(FakeSource(chunk = 5), sink, 1)
        assertEquals(7L, source.timedOpen(0, false) { 7 })
        assertEquals(5, source.read(ByteArray(8), 0, 8))
        assertEquals(2, sink.fails)
    }

    @Test
    fun aFaultInTheSinkFailureHandlerIsSwallowedToo() {
        val sink = ThrowingSink(failThrows = true)
        val source = CountingDataSource(FakeSource(), sink, 1)
        assertEquals(7L, source.timedOpen(0, false) { 7 })
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

private const val MS = 1_000_000L

/** A clock that moves only when told. */
private class StepClock : NanoClock {
    private var now = 0L

    fun advance(nanos: Long) {
        now += nanos
    }

    override fun now(): Long = now
}

private class ThrowingSink(
    private val failThrows: Boolean = false,
) : RequestSink {
    var fails = 0

    override fun opened(
        generation: Long,
        position: Long,
        durationMs: Long,
    ): Unit = error("counting fault")

    override fun read(
        generation: Long,
        count: Long,
        ioNanos: Long,
    ): Unit = error("counting fault")

    override fun fail() {
        fails++
        if (failThrows) error("fail fault")
    }
}

@UnstableApi
private class FakeSource(
    private val chunk: Int = -1,
    private val readFailure: IOException? = null,
    private val onRead: () -> Unit = {},
) : DataSource {
    var listener: TransferListener? = null
    var closes = 0
    val headers: Map<String, List<String>> = mapOf("a" to listOf("b"))

    override fun addTransferListener(transferListener: TransferListener) {
        listener = transferListener
    }

    override fun open(dataSpec: DataSpec): Long = -1

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        onRead()
        return readFailure?.let { throw it } ?: if (chunk >= 0) chunk else length
    }

    override fun getUri(): Uri? = null

    override fun getResponseHeaders(): Map<String, List<String>> = headers

    override fun close() {
        closes++
    }
}

@UnstableApi
private object NoListener : TransferListener {
    override fun onTransferInitializing(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) = Unit

    override fun onTransferStart(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) = Unit

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) = Unit

    override fun onTransferEnd(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) = Unit
}
