// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui.mark

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real loader over the bundled logos (spec 27): 97 channels (the 95 recipes and the two leftovers), colour and
 * mono, at 48 and 64 dp.
 */
class AssetMarkLoaderTest {
    private val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
    private val loader = AssetMarkLoader(assets)

    @Test
    fun keysListsNinetySeven() {
        val keys = loader.keys()
        assertEquals(97, keys.size)
        val shape = Regex("[a-z0-9-]+")
        assertTrue(keys.all { shape.matches(it) })
    }

    @Test
    fun at2xTheOneTwentyEightPixelRenderIsDecoded() {
        val bitmap = loader.load("little-farm")
        assertNotNull(bitmap)
        assertEquals(128, bitmap!!.width)
        assertEquals(72, bitmap.height)
    }

    @Test
    fun aLowerDensityDecodesTheNinetySixPixelRender() {
        for (density in listOf(1f, 1.5f)) {
            val bitmap = AssetMarkLoader(assets, density).load("little-farm")
            assertNotNull(bitmap)
            assertEquals("density $density", 96, bitmap!!.width)
            assertEquals("density $density", 54, bitmap.height)
        }
    }

    @Test
    fun everyKeyDecodesInBothVariantsAtEveryDensity() {
        for ((density, size) in listOf(1f to (96 to 54), 2f to (128 to 72), 3f to (128 to 72))) {
            val dense = AssetMarkLoader(assets, density)
            for (key in loader.keys()) {
                for (bitmap in listOf(dense.load(key), dense.loadMono(key))) {
                    assertNotNull("$key at $density", bitmap)
                    assertEquals("$key at $density", size.first, bitmap!!.width)
                    assertEquals("$key at $density", size.second, bitmap.height)
                }
            }
        }
    }

    @Test
    fun theMonoRenderIsAllWhiteWhereItIsOpaque() {
        val mono = loader.loadMono("masala-hour")!!.asAndroidBitmap()
        var opaque = 0
        for (y in 0 until mono.height) {
            for (x in 0 until mono.width) {
                val pixel = mono.getPixel(x, y)
                if (pixel ushr ALPHA_SHIFT == OPAQUE) {
                    opaque++
                    assertEquals("pixel $x,$y", WHITE, pixel)
                }
            }
        }
        assertTrue("the mark has solid pixels", opaque > 0)
    }

    @Test
    fun theColourRenderKeepsItsColour() {
        val colour = loader.load("masala-hour")!!.asAndroidBitmap()
        var coloured = 0
        for (y in 0 until colour.height) {
            for (x in 0 until colour.width) {
                val pixel = colour.getPixel(x, y)
                if (pixel ushr ALPHA_SHIFT == OPAQUE && pixel != WHITE) coloured++
            }
        }
        assertTrue("masala-hour has brand colours beyond white", coloured > 0)
    }

    @Test
    fun anUnknownKeyIsNullInBothVariants() {
        assertNull(loader.load("no-such-logo"))
        assertNull(loader.loadMono("no-such-logo"))
    }

    @Test
    fun aKeyWithPathCharactersIsNull() {
        assertNull(loader.load("../x"))
        assertNull(loader.load("a/b"))
        assertNull(loader.loadMono("../x"))
        assertNull(loader.loadMono("a/b"))
    }

    private companion object {
        const val ALPHA_SHIFT = 24
        const val OPAQUE = 0xFF
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
