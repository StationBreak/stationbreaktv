// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Spec 24: the warm handle a caller stamps on a source is optional and never shown. */
class PlayerSourceWarmKeyTest {
    private val source =
        PlayerSource(
            url = "https://server.example/secret?api_key=t0ken",
            isHls = false,
            playerStart = 5.seconds,
            subtitles = emptyList(),
            audioIndex = null,
            subtitleIndex = null,
            directPlayTracks = true,
            warmKey = "w7",
        )

    @Test
    fun theWarmKeyDefaultsToNone() {
        assertNull(source.copy(warmKey = null).warmKey)
        assertNull(
            PlayerSource(
                url = "u",
                isHls = true,
                playerStart = Duration.ZERO,
                subtitles = emptyList(),
                audioIndex = null,
                subtitleIndex = null,
                directPlayTracks = false,
            ).warmKey,
        )
    }

    @Test
    fun toStringShowsNoKeyAndNoUrl() {
        val text = source.toString()
        assertFalse("key shown: $text", text.contains("w7"))
        assertFalse("url shown: $text", text.contains("t0ken"))
        assertTrue(text.startsWith("PlayerSource("))
    }
}
