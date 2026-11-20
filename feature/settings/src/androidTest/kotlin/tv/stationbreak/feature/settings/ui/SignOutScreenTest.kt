// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.settings.AccountSignOut
import tv.stationbreak.feature.settings.SignOutViewModel
import java.util.concurrent.Executors

/**
 * The sign-out question (#336) over real key events: the focus starts on Stay signed in, so OK straight away (or OK
 * after OK) stays signed in; Down and OK signs out once and tells the host; Back stays signed in.
 */
@RunWith(AndroidJUnit4::class)
class SignOutScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val pool = Executors.newSingleThreadExecutor()
    private val work = pool.asCoroutineDispatcher()
    private var done = 0
    private var signedOut = 0

    // A signed-in server whose sign-out only counts.
    private class CountingSignOut : AccountSignOut {
        @Volatile var signOuts = 0

        override fun serverName(): String = "Home"

        override suspend fun signOut(): Boolean {
            signOuts += 1
            return true
        }
    }

    private val account = CountingSignOut()

    private fun show() {
        val vm = SignOutViewModel(account, work)
        compose.setContent {
            StationBreakTheme {
                // As in the app: the guide's root is focusable and the question takes the focus from it.
                Box(Modifier.requiredSize(WIDTH_DP.dp, HEIGHT_DP.dp).focusable()) {
                    SignOutRoot(vm, onDone = { done++ }, onSignedOut = { signedOut++ })
                }
            }
        }
        until { exists(SettingsTags.SIGN_OUT_STAY) }
    }

    @After
    fun tearDown() {
        pool.shutdownNow()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun until(condition: () -> Boolean) {
        compose.waitUntil(TIMEOUT_MS, condition)
        compose.waitForIdle()
    }

    private fun exists(tag: String): Boolean =
        compose
            .onAllNodes(hasTestTag(tag), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .isNotEmpty()

    @Test
    fun itAsks_withTheSafeAnswerFocused_andSaysWhoIsSignedIn() {
        show()
        compose.onNodeWithTag(SettingsTags.SIGN_OUT_TITLE, useUnmergedTree = true).assertTextEquals("Sign out?")
        compose.onNodeWithTag(SettingsTags.SIGNED_IN, useUnmergedTree = true).assertTextEquals("Signed in to Home")
        compose
            .onNodeWithTag(SettingsTags.SIGN_OUT_NOTE, useUnmergedTree = true)
            .assertTextEquals("You'll need to sign in again on this TV.")
        val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        compose
            .onNodeWithTag(SettingsTags.SIGN_OUT_STAY)
            .assertIsSelected()
            .assertContentDescriptionEquals("Stay signed in")
            .assert(button)
        compose
            .onNodeWithTag(SettingsTags.SIGN_OUT_CONFIRM)
            .assertIsNotSelected()
            .assertContentDescriptionEquals("Sign out")
            .assert(button)
        compose.onNodeWithTag(SettingsTags.SIGN_OUT_HINT).assertTextEquals("OK Choose   Back Stay signed in")
    }

    @Test
    fun okStraightAway_staysSignedIn() {
        show()
        press(Key.DirectionCenter)
        press(Key.DirectionCenter)
        assertEquals(1, done)
        assertEquals(0, account.signOuts)
        assertEquals(0, signedOut)
    }

    @Test
    fun backStaysSignedIn_evenOnSignOut() {
        show()
        press(Key.DirectionDown)
        compose.onNodeWithTag(SettingsTags.SIGN_OUT_CONFIRM).assertIsSelected()
        press(Key.Back)
        assertEquals(1, done)
        assertEquals(0, account.signOuts)
    }

    @Test
    fun downThenOk_signsOutOnce_andTellsTheHost() {
        show()
        press(Key.DirectionDown)
        compose.onNodeWithTag(SettingsTags.SIGN_OUT_STAY).assertIsNotSelected()
        compose.onNodeWithTag(SettingsTags.SIGN_OUT_CONFIRM).assertIsSelected()
        press(Key.DirectionCenter)
        until { signedOut == 1 }
        assertEquals(1, account.signOuts)
        assertEquals(0, done)
    }

    private companion object {
        const val WIDTH_DP = 960
        const val HEIGHT_DP = 540
        const val TIMEOUT_MS = 5_000L
    }
}
