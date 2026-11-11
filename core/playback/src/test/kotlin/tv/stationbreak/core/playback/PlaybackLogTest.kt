// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

@UnstableApi
class PlaybackLogTest {
    private fun playbackError(cause: Throwable) =
        PlaybackException("quotes track Secret Title", cause, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

    @Test
    fun `the top-level message is never logged, only the code name`() {
        val text = PlaybackLog.failureText(playbackError(UnknownHostException("Unable to resolve host nas.home.lan")))
        assertFalse(text.contains("Secret Title"))
        assertTrue(text.startsWith("code=ERROR_CODE_IO_UNSPECIFIED"))
    }

    @Test
    fun `an unknown host cause is its class name only`() {
        val text = PlaybackLog.failureText(playbackError(UnknownHostException("Unable to resolve host nas.home.lan")))
        assertEquals("code=ERROR_CODE_IO_UNSPECIFIED <- java.net.UnknownHostException", text)
    }

    @Test
    fun `a parser cause is its class name only`() {
        val parser = ParserException.createForMalformedManifest("bad line seg/1.ts?api_key=abc", null)
        val text = PlaybackLog.failureText(playbackError(parser))
        assertEquals("code=ERROR_CODE_IO_UNSPECIFIED <- androidx.media3.common.ParserException", text)
    }

    @Test
    fun `an audio sink cause keeps a redacted message and the top frame of the deepest cause`() {
        val write = AudioSink.WriteException(7, Format.Builder().build(), false)
        write.stackTrace = arrayOf(StackTraceElement("tv.stationbreak.Foo", "write", "Foo.kt", 42))
        val text = PlaybackLog.failureText(playbackError(write))
        assertTrue(text, text.contains("androidx.media3.exoplayer.audio.AudioSink\$WriteException: "))
        assertTrue(text, text.endsWith(" at=tv.stationbreak.Foo.write:42"))
    }

    @Test
    fun `a message kept for an audio cause is redacted and cut`() {
        assertFalse(PlaybackLog.scrub("failed Authorization: Bearer abc123").contains("abc123"))
        assertEquals(200, PlaybackLog.scrub("x".repeat(500)).length)
    }

    @Test
    fun `an illegal state from the levelling processor keeps its message`() {
        val error = IllegalStateException("bad frame count")
        error.stackTrace =
            arrayOf(StackTraceElement("tv.stationbreak.core.playback.LevellingAudioProcessor", "step", "L.kt", 9))
        val text = PlaybackLog.failureText(playbackError(error))
        assertTrue(text, text.contains("java.lang.IllegalStateException: bad frame count"))
        assertTrue(text, text.endsWith(" at=tv.stationbreak.core.playback.LevellingAudioProcessor.step:9"))
    }

    @Test
    fun `an illegal state from elsewhere is a class name only`() {
        val error = IllegalStateException("host nas.home.lan")
        error.stackTrace = arrayOf(StackTraceElement("okhttp3.Foo", "bar", "F.kt", 1))
        assertFalse(PlaybackLog.failureText(playbackError(error)).contains("nas.home.lan"))
    }

    @Test
    fun `stops at eight throwables even when the chain loops`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertEquals(PlaybackLog.MAX_CAUSES, PlaybackLog.failureText(b).split(" <- ").size)
    }
}
