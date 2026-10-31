// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.live.TransportView

/** The placeholder transport strip (spec 20): paused, minutes behind, a spinner while seeking, hidden when not visible. */
@RunWith(AndroidJUnit4::class)
class TransportStripTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // Live is 15 min into the block at NOW and the picture sits at 10 min: 5 min behind.
    private fun view(
        paused: Boolean = false,
        seeking: Boolean = false,
        visible: Boolean = true,
    ) = TransportView(
        paused = paused,
        seeking = seeking,
        seekable = true,
        visible = visible,
        mediaMs = 10 * MINUTE,
        targetMs = null,
        asOfMs = NOW,
        blockStartMs = NOW - 15 * MINUTE,
        inPointMs = 0,
        cutMs = 60 * MINUTE,
        driftMs = 0,
    )

    private fun show(view: TransportView?) {
        compose.setContent { StationBreakTheme { TransportStrip(view) { NOW } } }
        compose.waitForIdle()
    }

    @Test
    fun showsPausedWhilePaused() {
        show(view(paused = true))
        compose.onNodeWithTag(LiveTags.TRANSPORT).assertTextEquals("Paused")
    }

    @Test
    fun showsMinutesBehind() {
        show(view())
        compose.onNodeWithTag(LiveTags.TRANSPORT).assertTextEquals("5 min behind")
    }

    @Test
    fun showsASpinnerWhileSeeking() {
        show(view(seeking = true))
        compose.onNodeWithTag(LiveTags.TRANSPORT_SPINNER, useUnmergedTree = true).assertExists()
    }

    @Test
    fun hasNoSpinnerWhenNotSeeking() {
        show(view())
        compose.onNodeWithTag(LiveTags.TRANSPORT_SPINNER, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun isHiddenWhenNotVisible() {
        show(view(visible = false))
        compose.onNodeWithTag(LiveTags.TRANSPORT, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(LiveTags.TRANSPORT_SPINNER, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun isHiddenWithNoTransport() {
        show(null)
        compose.onNodeWithTag(LiveTags.TRANSPORT, useUnmergedTree = true).assertDoesNotExist()
    }

    private companion object {
        const val MINUTE = 60_000L
        const val NOW = 1_800_000_000_000L
    }
}
