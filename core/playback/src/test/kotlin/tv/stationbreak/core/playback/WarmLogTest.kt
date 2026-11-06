// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmLogTest {
    @Test
    fun memoryFieldsAreNumbersOnly() {
        assertTrue(Regex("""javaKb=\d+ javaMaxKb=\d+ nativeKb=\d+""").matches(WarmLog.memoryFields()))
    }

    @Test
    fun lineDoesNotThrowWithoutALogService() {
        WarmLog.line("standby built")
    }

    @Test
    fun aTestSinkSeesEveryLine() {
        val seen = mutableListOf<String>()
        WarmLog.sink = { seen += it }
        try {
            WarmLog.line("ready readyMs=1")
        } finally {
            WarmLog.sink = null
        }
        WarmLog.line("not seen")
        assertEquals(listOf("ready readyMs=1"), seen)
    }
}
