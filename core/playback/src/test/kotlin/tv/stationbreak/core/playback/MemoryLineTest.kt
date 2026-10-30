// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class MemoryLineTest {
    private val mib = 1_024L * 1_024L

    @Test
    fun theLineIsWholeMebibytesAndMillisecondsOnly() {
        val line =
            memoryLine(
                heapUsedBytes = 150 * mib + 1,
                heapMaxBytes = 192 * mib,
                playerBufferBytes = 48 * mib,
                poolBytes = 20 * mib,
                bufferedMs = 9_876,
            )
        assertEquals("mem heapMb=150 maxMb=192 playerBufMb=48 poolMb=20 bufferedMs=9876", line)
    }

    /** A scheduler that runs nothing by itself: the test fires what is pending. */
    private class FakeScheduler {
        val pending = mutableListOf<Pair<Runnable, Long>>()

        fun schedule(
            task: Runnable,
            delayMs: Long,
        ) {
            pending += task to delayMs
        }

        fun cancel(task: Runnable) {
            pending.removeAll { it.first === task }
        }

        fun fire() {
            val due = pending.toList()
            pending.clear()
            due.forEach { it.first.run() }
        }
    }

    private class Fixture {
        val scheduler = FakeScheduler()
        val allocator = PlaybackBuffering.mediaAllocator()
        val lines = mutableListOf<String>()
        var playing = true
        val sampler =
            MemorySampler(
                allocator = allocator,
                schedule = scheduler::schedule,
                cancel = scheduler::cancel,
                playing = { playing },
                bufferedMs = { 1_000L },
                log = { lines += it },
            )
    }

    @Test
    fun aPlaySchedulesASampleEveryIntervalReadingTheInjectedAllocator() {
        val f = Fixture()
        f.sampler.start()
        assertEquals(listOf(MemorySampler.INTERVAL_MS), f.scheduler.pending.map { it.second })
        // 2 MiB of 64 KiB pieces, half given back: 1 MiB in use, 1 MiB pooled, both still on the heap.
        val pieces = (1 * mib / C.DEFAULT_BUFFER_SEGMENT_SIZE).toInt()
        val given = List(pieces) { f.allocator.allocate() }
        repeat(pieces) { f.allocator.allocate() }
        given.forEach { f.allocator.release(it) }
        f.scheduler.fire()
        assertEquals(1, f.lines.size)
        assertTrue(f.lines[0], f.lines[0].contains("playerBufMb=1 poolMb=1 bufferedMs=1000"))
        assertEquals(1, f.scheduler.pending.size)
    }

    @Test
    fun aNewPlayNeverDoublesTheSchedule() {
        val f = Fixture()
        f.sampler.start()
        f.sampler.start()
        assertEquals(1, f.scheduler.pending.size)
    }

    @Test
    fun samplingEndsOnceNoPlayIsOn() {
        val f = Fixture()
        f.sampler.start()
        f.playing = false
        f.scheduler.fire()
        assertTrue(f.lines.isEmpty())
        assertTrue(f.scheduler.pending.isEmpty())
    }

    @Test
    fun stopCancelsTheNextSample() {
        val f = Fixture()
        f.sampler.start()
        f.sampler.stop()
        assertTrue(f.scheduler.pending.isEmpty())
    }
}
