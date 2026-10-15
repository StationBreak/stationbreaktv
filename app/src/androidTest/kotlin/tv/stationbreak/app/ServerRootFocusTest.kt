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
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.feature.onboarding.server.ServerTags

/** D-pad focus on the app's root, the server screen (retargeted from the hello-world screen, S0.2.2), on API 25. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ServerRootFocusTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun pressDpad(key: Key) = compose.onRoot().performKeyInput { pressKey(key) }

    @Test
    fun theAddressFieldHasFocusAtLaunch() {
        compose.onNodeWithTag(ServerTags.FIELD).assertIsFocused()
    }

    @Test
    fun dpadDownMovesFocusToTest() {
        pressDpad(Key.DirectionDown)
        compose.onNodeWithTag(ServerTags.TEST).assertIsFocused()
    }

    @Test
    fun dpadUpMovesFocusBackToTheField() {
        pressDpad(Key.DirectionDown)
        pressDpad(Key.DirectionUp)
        compose.onNodeWithTag(ServerTags.FIELD).assertIsFocused()
    }
}
