// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The ready notice (#776) informs and nothing more: it never takes a key from the screen under it. */
@RunWith(AndroidJUnit4::class)
class ChannelReadyNoticeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(
        channels: List<Pair<Int, String>>,
        keys: MutableList<Key> = mutableListOf(),
    ) {
        val focus = FocusRequester()
        compose.setContent {
            StationBreakTheme {
                Box(Modifier.fillMaxSize()) {
                    // The screen under the notice: it holds the focus and records every key it is given.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .focusRequester(focus)
                            .onKeyEvent {
                                if (it.type == KeyEventType.KeyDown) keys += it.key
                                true
                            }.focusable(),
                    )
                    ChannelReadyNotice(channels)
                }
            }
        }
        compose.runOnIdle { focus.requestFocus() }
    }

    @Test
    fun oneChannelIsNamedByNumberAndName() {
        show(listOf(352 to "Neon Rain"))
        compose.onNodeWithText("352 Neon Rain is ready").assertIsDisplayed()
    }

    @Test
    fun severalChannelsAreCounted() {
        show(listOf(352 to "Neon Rain", 424 to "Noir"))
        compose.onNodeWithText("2 channels are ready").assertIsDisplayed()
    }

    @Test
    fun noChannelsDrawNothing() {
        show(emptyList())
        compose.onNodeWithTag(ChannelReadyNoticeTags.NOTICE).assertDoesNotExist()
    }

    @Test
    fun isAnnouncedPolitelyOnceAsItAppears() {
        show(listOf(352 to "Neon Rain"))
        val notice = compose.onNodeWithText("352 Neon Rain is ready").fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, notice.config[SemanticsProperties.LiveRegion])
    }

    @Test
    fun theKeysStillReachTheScreenUnderIt() {
        val keys = mutableListOf<Key>()
        show(listOf(352 to "Neon Rain"), keys)
        compose.onNodeWithText("352 Neon Rain is ready").assertIsDisplayed()

        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionCenter)
            pressKey(Key.Back)
        }
        compose.waitForIdle()

        assertEquals(listOf(Key.DirectionDown, Key.DirectionCenter, Key.Back), keys)
    }
}
