// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui.mark

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real loader over the bundled logos (spec 27). */
class AssetMarkLoaderTest {
    private val loader = AssetMarkLoader(InstrumentationRegistry.getInstrumentation().targetContext.assets)

    @Test
    fun keysListsNinetySeven() {
        val keys = loader.keys()
        assertEquals(97, keys.size)
        val shape = Regex("[a-z0-9-]+")
        assertTrue(keys.all { shape.matches(it) })
    }

    @Test
    fun loadsASixtyFourPixelBitmap() {
        val bitmap = loader.load("little-farm")
        assertNotNull(bitmap)
        assertEquals(64, bitmap!!.width)
        assertEquals(64, bitmap.height)
    }

    @Test
    fun everyOneOfTheNinetySevenDecodesToASixtyFourPixelBitmap() {
        for (key in loader.keys()) {
            val bitmap = loader.load(key)
            assertNotNull(key, bitmap)
            assertEquals(key, 64, bitmap!!.width)
            assertEquals(key, 64, bitmap.height)
        }
    }

    @Test
    fun anUnknownKeyIsNull() {
        assertNull(loader.load("no-such-logo"))
    }

    @Test
    fun aKeyWithPathCharactersIsNull() {
        assertNull(loader.load("../x"))
        assertNull(loader.load("a/b"))
    }
}
