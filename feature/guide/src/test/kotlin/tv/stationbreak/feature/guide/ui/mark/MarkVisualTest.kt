// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui.mark

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

private class StubBitmap : ImageBitmap {
    override val width = 64
    override val height = 64
    override val config = ImageBitmapConfig.Argb8888
    override val colorSpace: ColorSpace = ColorSpaces.Srgb
    override val hasAlpha = true

    override fun readPixels(
        buffer: IntArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        bufferOffset: Int,
        stride: Int,
    ) = Unit

    override fun prepareToDraw() = Unit
}

private class StubLoader(
    private val known: Set<String>,
) : MarkLoader {
    override fun keys(): Set<String> = known

    override fun load(key: String): ImageBitmap? = StubBitmap()
}

@OptIn(ExperimentalCoroutinesApi::class)
class MarkVisualTest {
    @Test
    fun markVisualIsALogoOnlyWhenTheBitmapIsCached() =
        runTest {
            val cache = MarkCache(StubLoader(setOf("a")), this, StandardTestDispatcher(testScheduler))
            assertEquals(MarkVisual.Initials, markVisual("a", cache))
            cache.request(listOf("a"))
            assertEquals(MarkVisual.Initials, markVisual("a", cache)) // requested, not yet loaded
            advanceUntilIdle()
            val logo = markVisual("a", cache)
            assertSame(cache.peek("a"), (logo as MarkVisual.Logo).image)
            assertEquals(MarkVisual.Initials, markVisual("unknown", cache))
            assertEquals(MarkVisual.Initials, markVisual(null, cache))
            assertEquals(MarkVisual.Initials, markVisual("a", null))
        }
}
