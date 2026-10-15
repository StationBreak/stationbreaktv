// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.libraries

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.onboarding.server.ServerHandOff
import tv.stationbreak.feature.onboarding.signin.SignInResult
import tv.stationbreak.feature.onboarding.signin.SignedInHandOff

/**
 * "Signed in as" on the API 25 emulator (spec 05, Unit 4): Continue has default focus, Switch user is one press to the
 * right, and the screen never signs anyone out by itself. A name the user typed is shown; with none, the server is.
 */
@RunWith(AndroidJUnit4::class)
class SignedInScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val server = FakeMediaServer()
    private val signedIn = SignedInHandOff()
    private val handOff = ServerHandOff()
    private val info = ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val https = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)
    private val session = Session(UserId("user1"), AccessToken("dummy-token-0001"), info, https, null, false)
    private var continued = 0
    private var switched = 0
    private var missing = 0

    private fun launch(username: String? = "sam") {
        signedIn.put(SignInResult(session, persisted = true, username = username))
        val viewModel = SignedInViewModel(SessionRepository(InMemorySecretStore(), server), signedIn, handOff, InMemoryServerSettings())
        compose.setContent {
            StationBreakTheme {
                SignedInRoot(
                    viewModel,
                    onContinue = { continued++ },
                    onSwitchUser = { switched++ },
                    onMissingSession = { missing++ },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun awaitFocused(tag: String) {
        compose.waitUntil(FOCUS_TIMEOUT_MS) { runCatching { compose.onNodeWithTag(tag).assertIsFocused() }.isSuccess }
    }

    private fun press(
        keyCode: Int,
        expectedFocus: String,
    ) {
        awaitFocused(expectedFocus)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    @Test
    fun continueHasDefaultFocusAndNamesTheUser() {
        launch()
        awaitFocused(SignedInTags.CONTINUE)
        compose.onNodeWithTag(SignedInTags.HEADLINE).assert(hasText("Signed in as sam"))
    }

    @Test
    fun withNoNameTheServerIsNamedInstead() {
        launch(username = null)
        awaitFocused(SignedInTags.CONTINUE)
        compose.onNodeWithTag(SignedInTags.HEADLINE).assert(hasText("Signed in to Home Server"))
    }

    @Test
    fun selectOnContinueCarriesOnWithoutSigningOut() {
        launch()
        press(KeyEvent.KEYCODE_DPAD_CENTER, SignedInTags.CONTINUE)
        compose.waitUntil(FOCUS_TIMEOUT_MS) { continued == 1 }
        assertEquals(0, switched)
        assertEquals(emptyList<Session>(), server.revokeCalls)
    }

    @Test
    fun switchUserIsOnePressToTheRightAndSignsOut() {
        launch()
        press(KeyEvent.KEYCODE_DPAD_RIGHT, SignedInTags.CONTINUE)
        press(KeyEvent.KEYCODE_DPAD_CENTER, SignedInTags.SWITCH_USER)
        compose.waitUntil(FOCUS_TIMEOUT_MS) { switched == 1 }
        assertEquals(listOf(session), server.revokeCalls)
        assertEquals(0, continued)
    }

    @Test
    fun noSignInHeldHandsBackToLaunchRouting() {
        val viewModel = SignedInViewModel(SessionRepository(InMemorySecretStore(), server), signedIn, handOff, InMemoryServerSettings())
        compose.setContent {
            StationBreakTheme {
                SignedInRoot(viewModel, onContinue = {}, onSwitchUser = {}, onMissingSession = { missing++ })
            }
        }
        compose.waitUntil(FOCUS_TIMEOUT_MS) { missing == 1 }
    }

    private companion object {
        const val FOCUS_TIMEOUT_MS = 5_000L
    }

    @Test
    fun withNoNameAndNoServerNameItSaysJustSignedIn() {
        compose.setContent { StationBreakTheme { SignedInScreen(SignedInUiState(null, ""), onContinue = {}, onSwitchUser = {}) } }
        awaitFocused(SignedInTags.CONTINUE)
        compose.onNodeWithTag(SignedInTags.HEADLINE).assert(hasText("Signed in"))
    }

    @Test
    fun bothButtonsStayFocusableWhileSigningOutAndContinueDoesNothingThen() {
        var carried = 0
        compose.setContent {
            StationBreakTheme {
                SignedInScreen(SignedInUiState("sam", "Home Server", switching = true), onContinue = { carried++ }, onSwitchUser = {})
            }
        }
        awaitFocused(SignedInTags.CONTINUE)
        compose.onNodeWithTag(SignedInTags.CONTINUE).assertIsEnabled()
        compose.onNodeWithTag(SignedInTags.SWITCH_USER).assertIsEnabled()
        press(KeyEvent.KEYCODE_DPAD_CENTER, SignedInTags.CONTINUE)
        assertEquals(0, carried)
        awaitFocused(SignedInTags.CONTINUE)
    }
}
