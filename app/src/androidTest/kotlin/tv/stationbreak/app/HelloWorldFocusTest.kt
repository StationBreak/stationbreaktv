// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** D-pad focus on the hello-world screen (S0.2.2), on the Insignia's Android version in CI. */
@RunWith(AndroidJUnit4::class)
class HelloWorldFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun pressDpad(key: Key) = compose.onRoot().performKeyInput { pressKey(key) }

    @Test
    fun firstItemHasFocusAtLaunch() {
        compose.onNodeWithTag(HELLO_WORLD_FIRST_TAG).assertIsFocused()
    }

    @Test
    fun dpadRightMovesFocusToTheSecondItem() {
        pressDpad(Key.DirectionRight)
        compose.onNodeWithTag(HELLO_WORLD_SECOND_TAG).assertIsFocused()
    }

    @Test
    fun dpadLeftMovesFocusBackToTheFirstItem() {
        pressDpad(Key.DirectionRight)
        pressDpad(Key.DirectionLeft)
        compose.onNodeWithTag(HELLO_WORLD_FIRST_TAG).assertIsFocused()
    }
}
