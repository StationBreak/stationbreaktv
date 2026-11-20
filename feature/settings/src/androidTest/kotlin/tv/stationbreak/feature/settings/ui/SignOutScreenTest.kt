// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.LiveRegionMode
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
import kotlinx.coroutines.CompletableDeferred
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

        @Volatile var gate: CompletableDeferred<Unit>? = null

        override fun serverName(): String = "Home"

        override suspend fun signOut(): Boolean {
            signOuts += 1
            gate?.await()
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

    // Read when it opens: the question, the note, then the answer under the D-pad, by the one live region (the root).
    @Test
    fun itIsReadWhenItOpens_questionAndNoteOnce_thenOnlyTheFocusedAnswer() {
        show()
        compose
            .onNodeWithTag(SettingsTags.SIGN_OUT)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, QUESTION_AND_NOTE))
            .assertContentDescriptionEquals("Stay signed in")
        // On open nothing is a live region: the pane title and the root's description say it once.
        assertEquals(0, liveRegions())
        // Each move is said, short: the answers' live region holds only the answer, never the question again.
        press(Key.DirectionDown)
        compose.onNodeWithTag(SettingsTags.SIGN_OUT).assertContentDescriptionEquals("Sign out")
        answers().assertContentDescriptionEquals("Sign out").assert(polite)
        assertEquals(1, liveRegions())
        press(Key.DirectionUp)
        answers().assertContentDescriptionEquals("Stay signed in").assert(polite)
        assertEquals(1, liveRegions())
    }

    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    private fun answers() = compose.onNodeWithTag(SettingsTags.SIGN_OUT_ANSWERS, useUnmergedTree = true)

    // While it runs, the status line (the one live region) says so, and the hint offers nothing: Back cannot keep a
    // sign-out that has begun.
    @Test
    fun whileItRuns_theStatusSaysSo_andTheHintOffersNoWayBack() {
        account.gate = CompletableDeferred()
        show()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        until { exists(SettingsTags.SIGN_OUT_STATUS) }
        compose
            .onNodeWithTag(SettingsTags.SIGN_OUT_STATUS, useUnmergedTree = true)
            .assertTextEquals("Signing out…")
            .assert(polite)
        // Only the status line is live while it runs: the answers say nothing, and "Signing out…" is said once.
        assertEquals(1, liveRegions())
        answers().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        compose.onNodeWithTag(SettingsTags.SIGN_OUT_CONFIRM).assertContentDescriptionEquals("Sign out")
        compose.onNodeWithTag(SettingsTags.SIGN_OUT_HINT).assertTextEquals("")
        press(Key.Back)
        assertEquals(0, done)
        account.gate?.complete(Unit)
        until { signedOut == 1 }
    }

    private fun liveRegions(): Int =
        compose
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size

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
        const val QUESTION_AND_NOTE = "Sign out? You'll need to sign in again on this TV."
        const val WIDTH_DP = 960
        const val HEIGHT_DP = 540
        const val TIMEOUT_MS = 5_000L
    }
}
