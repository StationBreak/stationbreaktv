// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.ui.GuideColors
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.ScreenLayout

/** The Libraries backdrop is the guide's page in each theme, and leaves the picture's hole clear for live TV. */
@RunWith(AndroidJUnit4::class)
class LibrariesBackdropTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dark_standsOnThePage_andLeavesTheHoleClear() = check(GuideColors.Dark)

    @Test
    fun light_standsOnThePage_andLeavesTheHoleClear() = check(GuideColors.Light)

    private fun check(colors: GuideColors) {
        compose.setContent {
            CompositionLocalProvider(LocalGuideColors provides colors) {
                // A stand-in for the live picture, behind the compose layer's backdrop.
                Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp).background(PICTURE).testTag("host")) {
                    LibrariesBackdrop(HOLE) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        compose.waitForIdle()
        val density = compose.activity.resources.displayMetrics.density
        // Read from the window's own view tree, drawn now: a screenshot of the display can be taken before the first
        // frame with this content has reached the screen, and then shows the window's own background.
        val decor = compose.activity.window.decorView
        val frame = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { decor.draw(Canvas(frame)) }
        val bounds = compose.onNodeWithTag("host").fetchSemanticsNode().boundsInWindow

        fun at(
            dpX: Float,
            dpY: Float,
        ): Int = frame.getPixel((bounds.left + dpX * density).toInt(), (bounds.top + dpY * density).toInt())
        // The hole shows what is behind; the panel's side shows the page (Light's page is not black).
        val centreX = HOLE.left.value + (HOLE.right.value - HOLE.left.value) / 2
        val centreY = HOLE.top.value + (HOLE.bottom.value - HOLE.top.value) / 2
        assertEquals("hole", PICTURE.toArgb(), at(centreX, centreY))
        val page = at(PAGE_X_DP, HEIGHT_DP - PAGE_X_DP)
        assertEquals("page", colors.page.toArgb(), page)
    }

    private companion object {
        const val WIDTH_DP = 960
        const val HEIGHT_DP = 540
        const val PAGE_X_DP = 10f
        val PICTURE = Color(0xFFFF00FF)
        val HOLE = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(WIDTH_DP.dp, HEIGHT_DP.dp)))
    }
}
