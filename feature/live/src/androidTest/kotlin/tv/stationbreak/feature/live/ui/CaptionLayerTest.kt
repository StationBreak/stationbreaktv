// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A cue the player reports reaches the caption view and is drawn in the picture's rectangle; no cue, or the guide
 * open (hidden), draws nothing; the view takes no focus.
 */
@RunWith(AndroidJUnit4::class)
class CaptionLayerTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val cues = mutableStateOf<List<Cue>>(emptyList())
    private val hidden = mutableStateOf(false)

    private fun show() {
        compose.setContent {
            val surface = remember { SurfaceView(compose.activity) }
            Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp)) {
                VideoFrame(surface, ASPECT, cues.value, hidden.value)
            }
        }
        compose.waitForIdle()
    }

    private fun captionView(): SubtitleView {
        var found: SubtitleView? = null

        fun walk(view: View) {
            if (view is SubtitleView) found = view
            if (view is ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
        }
        compose.runOnUiThread { walk(compose.activity.window.decorView) }
        return checkNotNull(found) { "no SubtitleView in the picture's frame" }
    }

    /** How many pixels the caption view draws now: it paints only the cues' text, on a transparent background. */
    private fun drawnPixels(view: SubtitleView): Int {
        var count = 0
        compose.runOnUiThread {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            count = pixels.count { Color.alpha(it) != 0 }
        }
        return count
    }

    private fun cue(text: String) = Cue.Builder().setText(text).build()

    @Test
    fun aCueTheStateHoldsIsDrawnAndNoneDrawsNothing() {
        show()
        val view = captionView()
        assertNotNull(view)
        assertEquals("nothing to show: nothing drawn", 0, drawnPixels(view))

        cues.value = listOf(cue("Hello there"))
        compose.waitForIdle()
        assertTrue("the cue reached the view and was drawn", drawnPixels(view) > 0)

        cues.value = emptyList()
        compose.waitForIdle()
        assertEquals("the cue went: nothing drawn", 0, drawnPixels(view))
    }

    @Test
    fun theCaptionsAreHiddenWhileTheGuideIsOpenAndComeBack() {
        show()
        val view = captionView()
        cues.value = listOf(cue("Hello there"))
        compose.waitForIdle()
        assertTrue(drawnPixels(view) > 0)

        hidden.value = true
        compose.waitForIdle()
        assertEquals("the guide is open: no captions", 0, drawnPixels(view))

        hidden.value = false
        compose.waitForIdle()
        assertTrue("the guide closed: they are back", drawnPixels(view) > 0)
    }

    @Test
    fun theCaptionViewIsInThePicturesRectangleAndTakesNoFocus() {
        show()
        val view = captionView()
        // A 16:9 picture in a 4:3 box: the caption view has the picture's shape, not the box's.
        val shape = view.width.toFloat() / view.height
        assertEquals(ASPECT, shape, SHAPE_TOLERANCE)
        assertTrue(!view.isFocusable)
        assertTrue(!view.isClickable)
    }

    private companion object {
        const val WIDTH_DP = 640
        const val HEIGHT_DP = 480
        const val ASPECT = 16f / 9f
        const val SHAPE_TOLERANCE = 0.02f
    }
}
