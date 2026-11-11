// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * What the guide will draw as a logo: the bytes are the server's, so a size is read before any pixel is made, and an
 * image over the bounds, or not an image at all, is "no logo" and never an exception or an out-of-memory.
 */
@RunWith(AndroidJUnit4::class)
class GuideLogoDecoderTest {
    private fun png(
        width: Int,
        height: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // Alpha only: a flat image is a few hundred bytes of PNG however many pixels it claims.
        Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    @Test
    fun aLogoSizedImageIsDecoded() {
        val image = AndroidLogoDecoder.decode(png(420, 96))

        assertNotNull(image)
        assertEquals(420, image!!.width)
        assertEquals(96, image.height)
    }

    @Test
    fun theLargestAllowedImageIsStillDecoded() {
        assertNotNull(AndroidLogoDecoder.decode(png(2_000, 500)))
        assertNotNull(AndroidLogoDecoder.decode(png(AndroidLogoDecoder.MAX_WIDTH, 100)))
    }

    @Test
    fun anImageOverAnyBoundIsRefusedWithoutBeingDecoded() {
        // Too wide, too tall, and within both but over the pixel budget.
        assertNull(AndroidLogoDecoder.decode(png(AndroidLogoDecoder.MAX_WIDTH + 1, 10)))
        assertNull(AndroidLogoDecoder.decode(png(10, AndroidLogoDecoder.MAX_HEIGHT + 1)))
        assertNull(AndroidLogoDecoder.decode(png(2_048, 500)))
    }

    @Test
    fun bytesThatAreNotAnImageAreNoLogo() {
        assertNull(AndroidLogoDecoder.decode(ByteArray(0)))
        assertNull(AndroidLogoDecoder.decode("<html>not a picture</html>".toByteArray()))
        assertNull(AndroidLogoDecoder.decode(ByteArray(2_048) { it.toByte() }))
    }

    @Test
    fun aTruncatedImageNeverThrowsAndStaysWithinTheBounds() {
        val whole = png(420, 96)

        // The platform may draw what it has or give nothing; either way it is inside the bounds and no exception.
        val image = AndroidLogoDecoder.decode(whole.copyOf(whole.size / 2))
        if (image != null) assertEquals(420, image.width)
    }
}
