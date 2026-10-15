// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.signin

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.QuickConnectState
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SignInRequest
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.onboarding.server.ConnectedServer
import tv.stationbreak.feature.onboarding.server.ServerHandOff

/** A [MediaServer] whose Quick Connect can be driven by the test. */
private class DrivenServer(
    val fake: FakeMediaServer = FakeMediaServer(),
) : MediaServer by fake {
    val quickConnectUpdates = MutableSharedFlow<QuickConnectState>()
    var useDrivenQuickConnect = false
    var signInGate: CompletableDeferred<ServerResult<Session>>? = null

    override suspend fun signIn(request: SignInRequest): ServerResult<Session> {
        val held = signInGate ?: return fake.signIn(request)
        return held.await()
    }

    override fun quickConnect(
        address: ServerAddress,
        acknowledgement: CleartextAcknowledgement?,
    ): Flow<QuickConnectState> {
        val scripted = fake.quickConnect(address, acknowledgement)
        return if (useDrivenQuickConnect) quickConnectUpdates else scripted
    }
}

/**
 * The sign-in screen's D-pad behaviour on the API 25 emulator, driven with real key events (the way a remote sends
 * them): default focus, focus order, the keyboard's Next and Done, where focus lands after each outcome, Quick Connect,
 * Back, the cleartext notice, and what happens when the server has been lost.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions") // One function per behaviour.
class SignInScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val server = DrivenServer()
    private val handOff = ServerHandOff()
    private val info = ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val https = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)
    private val session = Session(UserId("user1"), AccessToken("dummy-token-0001"), info, https, null, false)
    private val signedIn = mutableListOf<SignInResult>()
    private var editedAddress = 0
    private var missing = 0
    private lateinit var viewModel: SignInViewModel

    private fun launch(connected: ConnectedServer? = ConnectedServer(ResolvedServer(https, info), null)) {
        if (connected != null) handOff.put(connected)
        viewModel = SignInViewModel(server, SessionRepository(InMemorySecretStore(), server), handOff)
        compose.setContent {
            StationBreakTheme {
                SignInRoot(
                    viewModel = viewModel,
                    onEditAddress = { editedAddress++ },
                    onMissingServer = { missing++ },
                    onSignedIn = { signedIn += it },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    private fun type(
        name: String = "sam",
        password: String = "dummy-pass-0001",
    ) {
        compose.runOnIdle {
            viewModel.onUsernameChange(name)
            viewModel.onPasswordChange(password)
        }
        compose.waitForIdle()
    }

    /** From the username: Down, Down to Sign in, Select. */
    private fun submit() {
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun editableText(tag: String): String =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text

    private val politeLiveRegion = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    /** From the username: Down, Down, Right to Quick Connect, Select. */
    private fun chooseQuickConnect() {
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    // Focus

    @Test
    fun theUsernameHasFocusAtLaunch() {
        launch()
        compose.onNodeWithTag(SignInTags.USERNAME).assertIsFocused()
    }

    @Test
    fun theFocusOrderIsUsernamePasswordSignInThenQuickConnect() {
        launch()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag(SignInTags.PASSWORD).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag(SignInTags.SIGN_IN).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag(SignInTags.QUICK_CONNECT).assertIsFocused()
    }

    @Test
    fun upRetracesTheOrderAndNothingTrapsFocus() {
        launch()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag(SignInTags.QUICK_CONNECT).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithTag(SignInTags.PASSWORD).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithTag(SignInTags.USERNAME).assertIsFocused()
    }

    @Test
    fun theShowToggleSitsBesideThePasswordAndRevealsIt() {
        launch()
        type(password = "abc123")
        compose.onNodeWithTag(SignInTags.PASSWORD).assert(hasText("••••••"))
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag(SignInTags.SHOW_PASSWORD).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag(SignInTags.PASSWORD).assert(hasText("abc123"))
        compose.onNodeWithTag(SignInTags.SHOW_PASSWORD).assert(hasText("Hide"))
    }

    // The keyboard

    @Test
    fun nextOnTheUsernameMovesFocusToThePassword() {
        launch()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag(SignInTags.USERNAME_INPUT).assert(hasImeAction(ImeAction.Next))
        compose.onNodeWithTag(SignInTags.USERNAME_INPUT).performImeAction()
        compose.waitForIdle()
        compose.onNodeWithTag(SignInTags.PASSWORD).assertIsFocused()
    }

    @Test
    fun doneOnThePasswordMovesFocusToSignIn() {
        launch()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag(SignInTags.PASSWORD_INPUT).assert(hasImeAction(ImeAction.Done))
        compose.onNodeWithTag(SignInTags.PASSWORD_INPUT).performImeAction()
        compose.waitForIdle()
        compose.onNodeWithTag(SignInTags.SIGN_IN).assertIsFocused()
    }

    // Password sign-in

    @Test
    fun aBlankUsernameShowsUnderTheFieldAndFocusReturnsToIt() {
        launch()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Type your username", substring = true).assertExists()
        compose.onNodeWithTag(SignInTags.USERNAME).assertIsFocused()
        assertTrue(server.fake.signInCalls.isEmpty())
    }

    @Test
    fun aSuccessfulSignInHandsTheSessionOverOnce() {
        server.fake.signInResult = ServerResult.Success(session)
        launch()
        type()
        submit()
        compose.waitForIdle()
        assertSame(session, signedIn.single().session)
        compose.onNodeWithTag(SignInTags.SIGNED_IN).assertExists()
        assertEquals(listOf("sam"), server.fake.signInCalls.map { it.second })
    }

    @Test
    fun wrongCredentialsKeepTheUsernameEmptyThePasswordAndFocusIt() {
        server.fake.signInResult = ServerResult.Failure(MediaServerError.HttpUnauthorized())
        launch()
        type()
        submit()
        compose.onNodeWithText("Wrong username or password", substring = true).assertExists()
        compose.onNodeWithTag(SignInTags.PASSWORD).assertIsFocused()
        compose.onNodeWithTag(SignInTags.USERNAME).assert(hasText("sam"))
        compose.onNodeWithTag(SignInTags.PASSWORD).assert(hasText("Your Jellyfin password"))
        compose.onNodeWithTag(SignInTags.EDIT_ADDRESS).assertDoesNotExist()
    }

    @Test
    fun forbiddenReadsDifferentlyFromWrongCredentials() {
        server.fake.signInResult = ServerResult.Failure(MediaServerError.HttpForbidden())
        launch()
        type()
        submit()
        compose.onNodeWithText("refused this sign-in", substring = true).assertExists()
        compose.onNodeWithText("Wrong username or password", substring = true).assertDoesNotExist()
        compose.onNodeWithTag(SignInTags.PASSWORD).assertIsFocused()
    }

    @Test
    fun aProblemWithTheAddressFocusesEditAddressWhichGoesBack() {
        server.fake.signInResult = ServerResult.Failure(MediaServerError.DnsFailure(listOf("UnknownHostException")))
        launch()
        type()
        submit()
        compose.onNodeWithTag(SignInTags.EDIT_ADDRESS).assertIsFocused()
        compose.onNodeWithText("Name lookup failed (UnknownHostException)").assertExists()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, editedAddress)
    }

    @Test
    fun aProblemThatMayPassFocusesThePasswordAndKeepsEditAddressBelow() {
        server.fake.signInResult = ServerResult.Failure(MediaServerError.Timeout(listOf("SocketTimeoutException")))
        launch()
        type()
        submit()
        compose.onNodeWithTag(SignInTags.PASSWORD).assertIsFocused()
        compose.onNodeWithText("Timed out (SocketTimeoutException)").assertExists()
        compose.onNodeWithTag(SignInTags.EDIT_ADDRESS).assertExists()
        // Down from the password reaches Sign in, which tries again; Edit address is below that.
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag(SignInTags.SIGN_IN).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag(SignInTags.EDIT_ADDRESS).assertIsFocused()
    }

    @Test
    fun theSignedInStatusReplacesTheForm() {
        server.fake.signInResult = ServerResult.Success(session)
        launch()
        type()
        submit()
        compose.onNodeWithTag(SignInTags.SIGNED_IN).assertExists()
        compose.onNodeWithTag(SignInTags.USERNAME).assertDoesNotExist()
        compose.onNodeWithTag(SignInTags.PASSWORD).assertDoesNotExist()
        compose.onNodeWithTag(SignInTags.SIGN_IN).assertDoesNotExist()
        compose.onNodeWithTag(SignInTags.QUICK_CONNECT).assertDoesNotExist()
    }

    // What a screen reader gets

    @Test
    fun theFieldsAreNamedAndAnUnmaskedValueIsSaidAsTheirState() {
        launch()
        type(name = "demo-user", password = "dummy-pass-0001")
        compose.onNodeWithTag(SignInTags.USERNAME).assert(hasContentDescription("Username"))
        compose.onNodeWithTag(SignInTags.USERNAME).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "demo-user"),
        )
        compose.onNodeWithTag(SignInTags.PASSWORD).assert(hasContentDescription("Password"))
        // A masked password is never spoken.
        compose
            .onNodeWithTag(SignInTags.PASSWORD)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun theShowToggleSaysWhatItDoes() {
        launch()
        compose.onNodeWithTag(SignInTags.SHOW_PASSWORD).assert(hasContentDescription("Show password"))
        compose.runOnIdle { viewModel.onToggleShowPassword() }
        compose.onNodeWithTag(SignInTags.SHOW_PASSWORD).assert(hasContentDescription("Hide password"))
    }

    @Test
    fun messagesThatAppearOnTheirOwnAreLiveRegions() {
        server.fake.signInResult = ServerResult.Failure(MediaServerError.HttpUnauthorized())
        launch()
        type()
        submit()
        compose.onNodeWithTag(SignInTags.ERROR_PANEL).assert(politeLiveRegion)
    }

    @Test
    fun theQuickConnectNoticeAndTheSignedInStatusAreLiveRegions() {
        server.fake.quickConnectStates = listOf(QuickConnectState.CodeIssued("123456"), QuickConnectState.Expired)
        launch()
        chooseQuickConnect()
        compose.onNodeWithTag(SignInTags.NOTICE).assert(politeLiveRegion)
        compose.onNodeWithText("Quick Connect code expired").assertExists()
    }

    @Test
    fun theSignedInStatusIsALiveRegion() {
        server.fake.signInResult = ServerResult.Success(session)
        launch()
        type()
        submit()
        compose.onNodeWithTag(SignInTags.SIGNED_IN).assert(politeLiveRegion)
    }

    @Test
    fun theWaitingLineIsALiveRegion() {
        server.useDrivenQuickConnect = true
        launch()
        chooseQuickConnect()
        runBlocking { server.quickConnectUpdates.emit(QuickConnectState.CodeIssued("123456")) }
        compose.waitForIdle()
        compose.onNodeWithTag(SignInTags.QUICK_CONNECT_WAITING).assert(politeLiveRegion)
    }

    @Test
    fun theSigningInLineIsALiveRegion() {
        val gate = CompletableDeferred<ServerResult<Session>>()
        server.signInGate = gate
        launch()
        type()
        submit()
        compose.onNodeWithTag(SignInTags.SIGNING_IN).assert(politeLiveRegion)
        gate.complete(ServerResult.Failure(MediaServerError.HttpUnauthorized()))
    }

    // An outside change reaches an open field

    @Test
    fun aPasswordClearedFromOutsideWhileTheFieldIsOpenEmptiesTheField() {
        launch()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag(SignInTags.PASSWORD_INPUT).performTextInput("dummy-pass-0001")
        compose.waitForIdle()
        assertFalse(editableText(SignInTags.PASSWORD_INPUT).isEmpty())

        compose.runOnIdle { viewModel.onLeave() }
        compose.waitForIdle()

        assertEquals("", editableText(SignInTags.PASSWORD_INPUT))
    }

    // Quick Connect

    @Test
    fun quickConnectShowsTheCodeInLargeTypeWithCancelFocused() {
        server.useDrivenQuickConnect = true
        launch()
        chooseQuickConnect()
        compose.onNodeWithTag(SignInTags.QUICK_CONNECT_CANCEL).assertIsFocused()
        runBlocking { server.quickConnectUpdates.emit(QuickConnectState.CodeIssued("123456")) }
        compose.waitForIdle()
        compose.onNodeWithTag(SignInTags.QUICK_CONNECT_CODE).assert(hasText("123456"))
        compose.onNodeWithText("another device", substring = true).assertExists()
        compose.onNodeWithTag(SignInTags.QUICK_CONNECT_CANCEL).assertIsFocused()
    }

    @Test
    fun cancelReturnsToTheFormWithFocusOnQuickConnect() {
        server.useDrivenQuickConnect = true
        launch()
        chooseQuickConnect()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag(SignInTags.QUICK_CONNECT).assertIsFocused()
        compose.onNodeWithTag(SignInTags.USERNAME).assertExists()
    }

    @Test
    fun backDuringQuickConnectCancelsItBeforeLeavingTheScreen() {
        server.useDrivenQuickConnect = true
        launch()
        chooseQuickConnect()
        press(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag(SignInTags.QUICK_CONNECT).assertIsFocused()
        assertEquals(SignInPhase.Idle, viewModel.state.value.phase)
    }

    @Test
    fun quickConnectDisabledSaysSoAndFocusesThePassword() {
        server.fake.quickConnectStates = listOf(QuickConnectState.Failed(MediaServerError.QuickConnectDisabled()))
        launch()
        chooseQuickConnect()
        compose.onNodeWithText("Quick Connect is turned off", substring = true).assertExists()
        compose.onNodeWithTag(SignInTags.PASSWORD).assertIsFocused()
    }

    @Test
    fun anExpiredCodeExplainsItselfAndFocusesQuickConnect() {
        server.fake.quickConnectStates = listOf(QuickConnectState.CodeIssued("123456"), QuickConnectState.Expired)
        launch()
        chooseQuickConnect()
        compose.onNodeWithText("ran out of time", substring = true).assertExists()
        compose.onNodeWithText("Quick Connect code expired").assertExists()
        compose.onNodeWithTag(SignInTags.QUICK_CONNECT).assertIsFocused()
    }

    @Test
    fun anApprovedCodeHandsTheSessionOver() {
        server.fake.quickConnectStates =
            listOf(QuickConnectState.CodeIssued("123456"), QuickConnectState.SignedIn(session))
        launch()
        chooseQuickConnect()
        compose.waitForIdle()
        assertSame(session, signedIn.single().session)
    }

    // Cleartext

    @Test
    fun aPlainConnectionDoesNotSayNotEncrypted() {
        launch()
        compose.onNodeWithTag(SignInTags.NOT_ENCRYPTED).assertDoesNotExist()
    }

    @Test
    fun anAcceptedCleartextConnectionSaysNotEncryptedWhereThePasswordIsTyped() {
        val http = ServerAddress(Scheme.Http, "jellyfin.example.test", 8096)
        launch(ConnectedServer(ResolvedServer(http, info), CleartextAcknowledgement(http)))
        compose.onNodeWithTag(SignInTags.NOT_ENCRYPTED).assertExists()
        compose.onNodeWithText("Not encrypted (http)").assertExists()
    }

    // Leaving, and losing the server

    @Test
    fun leavingTheScreenClearsAPasswordThatWasTyped() {
        handOff.put(ConnectedServer(ResolvedServer(https, info), null))
        viewModel = SignInViewModel(server, SessionRepository(InMemorySecretStore(), server), handOff)
        var visible by mutableStateOf(true)
        compose.setContent {
            StationBreakTheme {
                if (visible) SignInRoot(viewModel, onEditAddress = {}, onMissingServer = {}, onSignedIn = {})
            }
        }
        type()
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        assertEquals("", viewModel.state.value.password)
    }

    @Test
    fun withoutAHandedOverServerTheScreenGoesBackAndShowsNoForm() {
        launch(connected = null)
        assertEquals(1, missing)
        compose.onNodeWithTag(SignInTags.USERNAME).assertDoesNotExist()
        assertTrue(server.fake.signInCalls.isEmpty())
    }
}
