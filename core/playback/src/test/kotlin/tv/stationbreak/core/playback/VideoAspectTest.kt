// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import androidx.media3.common.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoAspectTest {
    private val delta = 0.001f

    @Test
    fun `square pixels give the frame's own shape`() {
        assertEquals(16f / 9f, displayAspect(1920, 1080, 1f)!!, delta)
        assertEquals(4f / 3f, displayAspect(640, 480, 1f)!!, delta)
        assertEquals(2.39f, displayAspect(1912, 800, 1f)!!, delta)
    }

    @Test
    fun `an anamorphic frame is shown in its display shape`() {
        // 720x480 with a 32:27 pixel is 16:9, not 3:2.
        assertEquals(16f / 9f, displayAspect(720, 480, 32f / 27f)!!, delta)
    }

    @Test
    fun `no size is no shape`() {
        assertNull(displayAspect(VideoSize.UNKNOWN))
        assertNull(displayAspect(0, 1080, 1f))
        assertNull(displayAspect(1920, 0, 1f))
    }

    @Test
    fun `a pixel ratio that is no number counts as square`() {
        assertEquals(4f / 3f, displayAspect(640, 480, 0f)!!, delta)
        assertEquals(4f / 3f, displayAspect(640, 480, Float.NaN)!!, delta)
    }

    @Test
    fun `the shape is null until a size is known`() {
        assertNull(VideoAspectHolder().aspect.value)
    }

    @Test
    fun `the last shape is kept when the size becomes unknown`() {
        val holder = VideoAspectHolder()
        holder.onVideoSize(VideoSize(640, 480))
        holder.onVideoSize(VideoSize.UNKNOWN)
        assertEquals(4f / 3f, holder.aspect.value!!, delta)
    }

    @Test
    fun `a new size replaces the kept shape`() {
        val holder = VideoAspectHolder()
        holder.onVideoSize(VideoSize(640, 480))
        holder.onVideoSize(VideoSize.UNKNOWN)
        holder.onVideoSize(VideoSize(1920, 1080))
        assertEquals(16f / 9f, holder.aspect.value!!, delta)
    }
}
