// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.live.LiveState

/**
 * The video surface keeps the picture's shape (#599): a 4:3 picture is pillarboxed and a 2.39:1 one letterboxed in the
 * full screen and in the guide's corner, centred in whichever box it is in; the one surface view is never made again.
 */
@RunWith(AndroidJUnit4::class)
class VideoFrameTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val aspect = mutableStateOf<Float?>(null)
    private var host = Rect.Zero
    private var density = 1f
    private lateinit var surface: SurfaceView

    private fun showFull() {
        compose.setContent {
            StationBreakTheme {
                density = LocalDensity.current.density
                surface = SurfaceView(compose.activity)
                Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp).onGloballyPositioned { host = it.boundsInWindow() }) {
                    LiveScreen(LiveState.Watching(null)) { VideoFrame(surface, aspect.value) }
                }
            }
        }
    }

    private fun showGuide() {
        compose.setContent {
            StationBreakTheme {
                density = LocalDensity.current.density
                surface = SurfaceView(compose.activity)
                Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp).onGloballyPositioned { host = it.boundsInWindow() }) {
                    LiveScreen(LiveState.Watching(null), guide = { Box(Modifier.fillMaxSize()) }) {
                        VideoFrame(surface, aspect.value)
                    }
                }
            }
        }
    }

    /** The surface's bounds in the host's own pixels. */
    private fun bounds(): Rect {
        compose.waitForIdle()
        val at = IntArray(2)
        surface.getLocationInWindow(at)
        val left = at[0] - host.left
        val top = at[1] - host.top
        return Rect(left, top, left + surface.width, top + surface.height)
    }

    private fun assertFitted(
        box: DpRect,
        shape: Float,
    ) {
        val bx = box.left.value * density
        val by = box.top.value * density
        val bw = (box.right - box.left).value * density
        val bh = (box.bottom - box.top).value * density
        val seen = bounds()
        assertEquals("ratio", shape, seen.width / seen.height, TOLERANCE)
        assertEquals("centred across", bx + bw / 2, (seen.left + seen.right) / 2, 1.5f)
        assertEquals("centred down", by + bh / 2, (seen.top + seen.bottom) / 2, 1.5f)
        // Fitted: it touches the box on one axis and is within it on the other.
        val fills = seen.width >= bw - 1.5f || seen.height >= bh - 1.5f
        assertEquals("the larger fit", true, fills)
        assertEquals("inside across", true, seen.left >= bx - 1.5f && seen.right <= bx + bw + 1.5f)
        assertEquals("inside down", true, seen.top >= by - 1.5f && seen.bottom <= by + bh + 1.5f)
    }

    private val full = DpRect(0.dp, 0.dp, WIDTH_DP.dp, HEIGHT_DP.dp)
    private val corner = ScreenLayout.pictureRect(ScreenLayout.safeArea(DpSize(WIDTH_DP.dp, HEIGHT_DP.dp)))

    @Test
    fun noSizeYetFillsTheScreen() {
        showFull()
        val seen = bounds()
        assertEquals(WIDTH_DP * density, seen.width, 1.5f)
        assertEquals(HEIGHT_DP * density, seen.height, 1.5f)
    }

    @Test
    fun fourByThreeIsPillarboxedInTheScreen() {
        aspect.value = FOUR_THREE
        showFull()
        assertFitted(full, FOUR_THREE)
        assertEquals(HEIGHT_DP * density, bounds().height, 1.5f)
    }

    @Test
    fun wideScopeIsLetterboxedInTheScreen() {
        aspect.value = SCOPE
        showFull()
        assertFitted(full, SCOPE)
        assertEquals(WIDTH_DP * density, bounds().width, 1.5f)
    }

    @Test
    fun theGuidesCornerFitsThePictureWithinIt() {
        aspect.value = FOUR_THREE
        showGuide()
        assertFitted(corner, FOUR_THREE)
        aspect.value = SCOPE
        assertFitted(corner, SCOPE)
    }

    @Test
    fun theSurfaceIsTheSameViewWhenTheShapeChanges() {
        showFull()
        val before = surface
        aspect.value = FOUR_THREE
        compose.waitForIdle()
        aspect.value = SCOPE
        compose.waitForIdle()
        assertSame(before, surface)
        assertEquals(true, surface.isAttachedToWindow)
    }

    private companion object {
        const val WIDTH_DP = 960
        const val HEIGHT_DP = 540
        const val FOUR_THREE = 4f / 3f
        const val SCOPE = 2.39f
        const val TOLERANCE = 0.02f
    }
}
