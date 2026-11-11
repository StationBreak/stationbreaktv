// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackLogTest {
    @Test
    fun `names each cause in order`() {
        val error = IllegalStateException("outer", IllegalArgumentException("inner"))
        assertEquals(
            "java.lang.IllegalStateException: outer <- java.lang.IllegalArgumentException: inner",
            PlaybackLog.failureText(error),
        )
    }

    @Test
    fun `stops at eight causes even when the chain loops`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertEquals(PlaybackLog.MAX_CAUSES, PlaybackLog.failureText(b).split(" <- ").size)
    }

    @Test
    fun `a missing message reads as null`() {
        assertEquals("java.lang.RuntimeException: null", PlaybackLog.failureText(RuntimeException()))
    }

    @Test
    fun `addresses and tokens are blanked`() {
        val text = PlaybackLog.scrub("open https://host:8096/Videos/1/master.m3u8?api_key=abc failed; token=xyz")
        assertFalse(text.contains("host"))
        assertFalse(text.contains("abc"))
        assertFalse(text.contains("xyz"))
        assertTrue(text.contains("<url>"))
    }

    @Test
    fun `a long message is cut`() {
        assertEquals(200, PlaybackLog.scrub("x".repeat(1000)).length)
    }
}
