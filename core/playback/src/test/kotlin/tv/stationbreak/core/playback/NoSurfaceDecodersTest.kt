// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.content.Context
import android.content.ContextWrapper
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertFalse
import org.junit.Test

@UnstableApi
class NoSurfaceDecodersTest {
    // No Android runtime on the JVM: the renderer or the codec list throws, which reads as "no opinion".
    private val context: Context = ContextWrapper(null)

    @Test
    fun noOpinionOnTheJvmAndDoesNotThrow() {
        assertFalse(NoSurfaceDecoders.deviceCannot(context))
    }
}
