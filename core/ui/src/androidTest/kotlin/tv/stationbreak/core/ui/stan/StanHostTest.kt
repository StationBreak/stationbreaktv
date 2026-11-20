// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.stan

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.ui.LocalMotionReduced
import tv.stationbreak.core.ui.StationBreakTheme

/** The host is decoration: invisible to accessibility, never focusable, one line at a time, and still when asked. */
@RunWith(AndroidJUnit4::class)
class StanHostTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun hostHasNoSemantics() {
        compose.setContent { StationBreakTheme { StanHost(StanMood.Pleased, "THERE IT IS!") } }
        compose.onNodeWithText("THERE IT IS!").assertDoesNotExist()
        compose.onNodeWithText("THERE IT IS!", useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodes(isFocusable(), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aFrozenHostDrawsTheMomentItIsGivenAndRecordsIt() {
        // A host frozen on a moment (as the cheer's still is, on the moment the cheer last drew) draws that moment, and
        // its recorder says so: the draw phase writes it. Which moment a host draws, frozen or not, is pure logic and
        // is proven on the JVM (StanHostMomentTest); an animated host cannot be the source here, because its clock is
        // an infinite animation, which a Compose test holds still. Motion is on whatever the device says, so the
        // frozen time is not replaced by the reduced-motion still frame.
        val moment = StanHostMoment(seconds = 1.25, talking = false)
        val frozen = StanHostRecorder()
        compose.setContent {
            StationBreakTheme {
                CompositionLocalProvider(LocalMotionReduced provides false) {
                    StanHost(StanMood.Cheer, "ON THE AIR!", freezeAt = moment, recorder = frozen)
                }
            }
        }
        compose.waitUntil(DRAW_TIMEOUT_MS) { frozen.last == moment }

        // Time goes on: a frozen host has no clock, so whatever it draws is the same moment.
        compose.mainClock.advanceTimeBy(HALF_SECOND_MS * 2)
        compose.waitForIdle()
        assertEquals(moment, frozen.last)
    }

    @Test
    fun nullLineShowsNoBubble() {
        compose.setContent { StationBreakTheme { StanBubble(null, StanPalette.Dark) } }
        compose.onNodeWithTag(StanHostTags.BUBBLE, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun newLineReplacesOld() {
        var line by mutableStateOf("WHAT'S ON TONIGHT?")
        compose.setContent { StationBreakTheme { StanBubble(line, StanPalette.Dark) } }
        compose.onNodeWithTag(StanHostTags.BUBBLE, useUnmergedTree = true).assertTextEquals("WHAT'S ON TONIGHT?")

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { line = "THERE IT IS!" }
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithTag(StanHostTags.BUBBLE, useUnmergedTree = true).assertTextEquals("THERE IT IS!")
        compose.onAllNodesWithText("WHAT'S ON TONIGHT?", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun reducedMotionIsStill() {
        compose.setContent {
            StationBreakTheme {
                CompositionLocalProvider(LocalMotionReduced provides true) {
                    // The host has no semantics of its own, so a plain box around it gives the test its bounds.
                    Box(Modifier.testTag(HOST)) { StanHost(StanMood.Cheer, "YOU'RE IN!") }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val before = hostPixels()
        compose.mainClock.advanceTimeBy(HALF_SECOND_MS)
        compose.waitForIdle()
        val after = hostPixels()
        assertTrue("two frames 500 ms apart differ", before.sameAs(after))
    }

    // captureToImage needs PixelCopy of a window (API 26+) and the floor device is API 25, so read the screen, as the
    // guide's and the trust screen's tests do. A node's bounds in its window plus the window's place on the screen are
    // screen pixels.
    private fun hostPixels(): Bitmap {
        val bounds = compose.onNodeWithTag(HOST).fetchSemanticsNode().boundsInWindow
        val origin =
            IntArray(2).also {
                compose.activity.window.decorView
                    .getLocationOnScreen(it)
            }
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val left = origin[0] + bounds.left.toInt()
        val top = origin[1] + bounds.top.toInt()
        return Bitmap.createBitmap(screen, left, top, bounds.width.toInt(), bounds.height.toInt())
    }

    private companion object {
        const val HOST = "stanHostTest.host"
        const val HALF_SECOND_MS = 500L
        const val DRAW_TIMEOUT_MS = 5_000L
    }
}
