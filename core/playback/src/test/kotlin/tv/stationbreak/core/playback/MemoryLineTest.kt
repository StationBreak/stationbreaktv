// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryLineTest {
    @Test
    fun theLineIsWholeMebibytesAndMillisecondsOnly() {
        val mib = 1_024L * 1_024L
        val line =
            memoryLine(
                heapUsedBytes = 150 * mib + 1,
                heapMaxBytes = 192 * mib,
                playerBufferBytes = 48 * mib,
                bufferedMs = 9_876,
            )
        assertEquals("mem heapMb=150 maxMb=192 playerBufMb=48 bufferedMs=9876", line)
    }
}
