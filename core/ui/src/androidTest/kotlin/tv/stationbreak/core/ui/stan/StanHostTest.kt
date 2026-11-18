// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.stan

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
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
                    StanHost(StanMood.Cheer, "YOU'RE IN!")
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val before = compose.onRoot().captureToImage().asAndroidBitmap()
        compose.mainClock.advanceTimeBy(500)
        val after = compose.onRoot().captureToImage().asAndroidBitmap()
        assertTrue("two frames 500 ms apart differ", before.sameAs(after))
    }
}
