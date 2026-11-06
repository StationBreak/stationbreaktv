// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

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
}
