// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@UnstableApi
class PlayerFailureTest {
    @Test
    fun theEventCarriesTheStableErrorCodeNameNotAClassNameOrMessage() {
        val error =
            PlaybackException(
                "http://server/x.m3u8?api_key=secret",
                IllegalStateException("http://server/seg?api_key=secret"),
                PlaybackException.ERROR_CODE_DECODING_FAILED,
            )
        val event = playerFailure(error, 7)
        assertEquals("ERROR_CODE_DECODING_FAILED", event.errorCode)
        assertEquals(7L, event.generation)
        assertNull(event.httpStatus)
        assertFalse(event.toString().contains("secret"))
    }

    @Test
    fun aCauseCycleDoesNotLoopForever() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        val event = playerFailure(PlaybackException("m", b, PlaybackException.ERROR_CODE_UNSPECIFIED), 1)
        assertEquals("ERROR_CODE_UNSPECIFIED", event.errorCode)
    }
}
