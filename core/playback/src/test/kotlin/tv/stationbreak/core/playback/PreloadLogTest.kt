// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** Spike #563: an error line names classes and a message, never an address. */
class PreloadLogTest {
    @Test
    fun anErrorNamesItsClassesAndHidesEveryUrl() {
        val cause = IOException("could not read https://host.example:8096/Videos/1?api_key=x now")
        val text = PreloadLog.describe(IllegalStateException("open failed", cause))
        assertEquals("IllegalStateException>IOException: open_failed", text)
    }

    @Test
    fun aMessageKeepsTheTextAroundAUrl() {
        val text = PreloadLog.describe(IOException("failed to open http://a.b/c then gave up"))
        assertEquals("IOException: failed_to_open_<url>_then_gave_up", text)
    }
}
