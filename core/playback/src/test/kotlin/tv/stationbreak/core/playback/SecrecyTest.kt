// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.time.Duration

/** Nothing the player is handed or reports may show a URL when printed (ADR-0016, 1). */
@UnstableApi
class SecrecyTest {
    private val url = "https://server.example/Videos/1/master.m3u8?api_key=secret"

    @Test
    fun aPlayerSourceDoesNotPrintItsUrl() {
        val source =
            PlayerSource(url, true, Duration.ZERO, Duration.ZERO, emptyList(), null, null, false)
        assertFalse(source.toString().contains("secret"))
        assertFalse(source.toString().contains("server.example"))
    }

    @Test
    fun aSeparateSubtitleDoesNotPrintItsUrl() {
        val subtitle = SeparateSubtitle(url, "eng")
        assertFalse(subtitle.toString().contains("secret"))
        assertFalse(subtitle.toString().contains("server.example"))
        assertFalse(subtitle.toString().contains(url))
    }

    @Test
    fun aSourceWithSubtitlesDoesNotPrintTheirUrlsEither() {
        val source =
            PlayerSource(url, true, Duration.ZERO, Duration.ZERO, listOf(SeparateSubtitle(url, "eng")), null, null, false)
        assertFalse(source.toString().contains("secret"))
    }

    @Test
    fun media3LoggingIsSwitchedOff() {
        Log.setLogLevel(Log.LOG_LEVEL_ALL)
        silenceMedia3Logging()
        assertEquals(Log.LOG_LEVEL_OFF, Log.getLogLevel())
    }
}
