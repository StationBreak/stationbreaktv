// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultAllocator

private const val MIB = 1_024L * 1_024L

/**
 * The player's periodic memory line (`SBLIVE mem ...`), so a device run can see the heap against the player's own
 * media buffer without a heap dump: the heap in use and its limit, the buffer bytes holding media now
 * ([playerBufferBytes]), the bytes the allocator keeps pooled after the media drained ([poolBytes], still on the heap:
 * `DefaultAllocator` trims its pool only down to the byte target), and how much media is buffered. The player's share
 * of the heap is the sum of the two byte counts. Numbers and fixed names only.
 */
internal fun memoryLine(
    heapUsedBytes: Long,
    heapMaxBytes: Long,
    playerBufferBytes: Long,
    poolBytes: Long,
    bufferedMs: Long,
): String =
    "mem heapMb=${heapUsedBytes / MIB} maxMb=${heapMaxBytes / MIB} playerBufMb=${playerBufferBytes / MIB} " +
        "poolMb=${poolBytes / MIB} bufferedMs=$bufferedMs"

/**
 * Logs a [memoryLine] every [INTERVAL_MS] while [playing] holds, reading [allocator] (the player's own). [start] is
 * called for each play and never doubles the schedule; sampling ends by itself once no play is on, and [stop] (release)
 * cancels the next sample. [schedule] and [cancel] run tasks on the player's looper; all calls are made there.
 */
@UnstableApi
internal class MemorySampler(
    private val allocator: DefaultAllocator,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val playing: () -> Boolean,
    private val bufferedMs: () -> Long,
    private val log: (String) -> Unit,
) {
    private val sample = Runnable { tick() }

    private fun tick() {
        if (!playing()) return
        val runtime = Runtime.getRuntime()
        log(
            memoryLine(
                heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
                heapMaxBytes = runtime.maxMemory(),
                playerBufferBytes = allocator.totalBytesAllocated.toLong(),
                poolBytes = allocator.unusedBytesAllocated.toLong(),
                bufferedMs = bufferedMs(),
            ),
        )
        schedule(sample, INTERVAL_MS)
    }

    fun start() {
        cancel(sample)
        schedule(sample, INTERVAL_MS)
    }

    fun stop() = cancel(sample)

    companion object {
        const val INTERVAL_MS = 10_000L
    }
}
