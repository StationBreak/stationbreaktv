// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.server

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.CertificateEvidence
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.SpkiPin
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.onboarding.trust.CertificateTrust
import tv.stationbreak.feature.onboarding.trust.TrustTags

/** A [MediaServer] whose `discover` can be held open. */
private class GatedServer(
    val fake: FakeMediaServer = FakeMediaServer(),
) : MediaServer by fake {
    var gate: CompletableDeferred<ServerResult<ResolvedServer>>? = null

    override suspend fun discover(input: String): ServerResult<ResolvedServer> {
        val held = gate ?: return fake.discover(input)
        fake.discoverCalls += input
        return held.await()
    }
}

/**
 * The server screen's D-pad behaviour on the API 25 emulator, driven with real key events (the way a remote sends
 * them): default focus, focus order, where focus lands after each outcome, the cleartext dialog and Back.
 */
@RunWith(AndroidJUnit4::class)
class ServerScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val server = GatedServer()
    private val info = ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val https = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)
    private val continued = mutableListOf<ConnectedServer>()
    private lateinit var viewModel: ServerViewModel

    private fun launch() {
        val settings = InMemoryServerSettings()
        val certificateTrust = CertificateTrust(server, settings, SessionRepository(InMemorySecretStore(), server))
        viewModel = ServerViewModel(server, ServerHandOff(), settings, certificateTrust)
        compose.setContent {
            StationBreakTheme {
                val state by viewModel.state.collectAsState()
                ServerScreen(
                    state = state,
                    onInputChange = viewModel::onInputChange,
                    onTest = viewModel::onTest,
                    onCancel = viewModel::onCancel,
                    onEditAddress = viewModel::onEditAddress,
                    onConfirmCleartext = viewModel::onConfirmCleartext,
                    onDismissCleartext = viewModel::onDismissCleartext,
                    onTrustCertificate = viewModel::onTrustCertificate,
                    onDontTrust = viewModel::onDontTrust,
                    onContinue = { continued += it },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    private fun typeAndTest(text: String = "jellyfin.example.test") {
        compose.runOnIdle { viewModel.onInputChange(text) }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun found(address: ServerAddress = https) = ServerResult.Success(ResolvedServer(address, info))

    @Test
    fun theFieldHasFocusAtLaunch() {
        launch()
        compose.onNodeWithTag(ServerTags.FIELD).assertIsFocused()
    }

    @Test
    fun theAddressStartsEmptyWithAnExampleHint() {
        launch()
        compose.onNodeWithTag(ServerTags.FIELD).assert(hasText("For example, jellyfin.local:8096"))
    }

    @Test
    fun theFieldIsNamedForAScreenReader() {
        launch()
        compose.onNodeWithTag(ServerTags.FIELD).assert(hasContentDescription("Server address"))
    }

    @Test
    fun theTestingLineAndAnErrorPanelAreLiveRegions() {
        val gate = CompletableDeferred<ServerResult<ResolvedServer>>()
        server.gate = gate
        launch()
        typeAndTest()
        val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        compose.onNodeWithTag(ServerTags.TESTING).assert(polite)
        gate.complete(ServerResult.Failure(MediaServerError.Timeout()))
        compose.waitForIdle()
        compose.onNodeWithTag(ServerTags.ERROR_PANEL).assert(polite)
    }

    @Test
    fun downFromTheFieldMovesToTestAndUpComesBack() {
        launch()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag(ServerTags.TEST).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithTag(ServerTags.FIELD).assertIsFocused()
    }

    @Test
    fun selectOpensTheFieldForEditingAndDoneMovesFocusToTest() {
        launch()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag(ServerTags.FIELD_INPUT).assert(hasImeAction(ImeAction.Done))
        compose.onNodeWithTag(ServerTags.FIELD_INPUT).performImeAction()
        compose.waitForIdle()
        compose.onNodeWithTag(ServerTags.TEST).assertIsFocused()
    }

    @Test
    fun testingMovesFocusToCancelAndCancelWorks() {
        server.gate = CompletableDeferred()
        launch()
        typeAndTest()
        compose.onNodeWithTag(ServerTags.CANCEL).assertIsFocused()
        compose.onNodeWithTag(ServerTags.TEST).assertIsNotEnabled()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag(ServerTags.TEST).assertIsFocused()
        assertEquals(ServerPhase.Idle, viewModel.state.value.phase)
    }

    @Test
    fun aSuccessfulTestMovesFocusToContinueAndShowsNameAndVersion() {
        server.fake.discoverResult = found()
        launch()
        typeAndTest()
        compose.onNodeWithTag(ServerTags.CONTINUE).assertIsFocused()
        compose.onNodeWithText("Found Home Server").assertExists()
        compose.onNodeWithText("Jellyfin version 12.2.0").assertExists()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, continued.size)
        assertEquals(https, continued.single().server.address)
    }

    @Test
    fun aNetworkErrorMovesFocusToRetryAndEditAddressIsOnePressAway() {
        server.fake.discoverResult = ServerResult.Failure(MediaServerError.Timeout(listOf("SocketTimeoutException")))
        launch()
        typeAndTest()
        compose.onNodeWithTag(ServerTags.RETRY).assertIsFocused()
        compose.onNodeWithText("Timed out (SocketTimeoutException)").assertExists()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag(ServerTags.EDIT_ADDRESS).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag(ServerTags.FIELD).assertIsFocused()
        assertEquals(ServerPhase.Idle, viewModel.state.value.phase)
    }

    @Test
    fun aNotUsableErrorNamesTheVersionAndOffersOneAction() {
        server.fake.discoverResult = ServerResult.Failure(MediaServerError.UnsupportedVersion(ServerVersion(10, 11, 8)))
        launch()
        typeAndTest()
        compose.onNodeWithTag(ServerTags.EDIT_ADDRESS).assertIsFocused()
        compose
            .onNodeWithText(
                "This Jellyfin server is version 10.11.8. StationBreak needs 12.0 or newer, " +
                    "so the server needs updating.",
            ).assertExists()
        compose.onNodeWithTag(ServerTags.RETRY).assertDoesNotExist()
    }

    @Test
    fun aBlankAddressShowsUnderTheFieldAndSendsNothing() {
        launch()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag(ServerTags.FIELD_ERROR).assertExists()
        compose.onNodeWithText("Type your server", substring = true).assertExists()
        compose.onNodeWithTag(ServerTags.FIELD).assertIsFocused()
        assertTrue(server.fake.discoverCalls.isEmpty())
    }

    @Test
    fun testingABlankFieldAgainBringsFocusBackToTheField() {
        launch()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag(ServerTags.FIELD).assertIsFocused()
        // The second press produces the same state, so only the click itself can move focus.
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag(ServerTags.TEST).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag(ServerTags.FIELD).assertIsFocused()
        assertTrue(server.fake.discoverCalls.isEmpty())
    }

    @Test
    fun continueHandsTheConnectedServerToTheCaller() {
        // Continue goes nowhere yet (task 2.0 adds sign-in); this pins what it hands over.
        server.fake.discoverResult = found()
        launch()
        typeAndTest()
        compose.onNodeWithTag(ServerTags.CONTINUE).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(ConnectedServer(ResolvedServer(https, info), acknowledgement = null)), continued)
    }

    @Test
    fun theCleartextDialogFocusesCancelAndBackCancels() {
        server.fake.discoverResult = found(ServerAddress(Scheme.Http, "jellyfin.example.test", 80))
        launch()
        typeAndTest("http://jellyfin.example.test")
        compose.onNodeWithTag(ServerTags.CLEARTEXT_CANCEL).assertIsFocused()
        press(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag(ServerTags.CLEARTEXT_CANCEL).assertDoesNotExist()
        assertEquals(ServerPhase.Idle, viewModel.state.value.phase)
        assertTrue(continued.isEmpty())
    }

    @Test
    fun theFallbackCleartextDialogHasItsOwnWording() {
        server.fake.discoverResult = found(ServerAddress(Scheme.Http, "192.0.2.10", 8096, viaFallback = true))
        launch()
        typeAndTest("192.0.2.10:8096")
        compose.onNodeWithText("so it tried http", substring = true).assertExists()
        compose.onNodeWithTag(ServerTags.CLEARTEXT_CANCEL).assertIsFocused()
    }

    @Test
    fun confirmingCleartextShowsContinue() {
        server.fake.discoverResult = found(ServerAddress(Scheme.Http, "jellyfin.example.test", 80))
        launch()
        typeAndTest("http://jellyfin.example.test")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag(ServerTags.CLEARTEXT_CONFIRM).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag(ServerTags.CONTINUE).assertIsFocused()
        compose.onNodeWithTag(ServerTags.NOT_ENCRYPTED).assertExists()
        compose.onNodeWithText("Not encrypted (http)").assertExists()
    }

    @Test
    fun aPlainSuccessDoesNotSayNotEncrypted() {
        server.fake.discoverResult = found()
        launch()
        typeAndTest()
        compose.onNodeWithTag(ServerTags.NOT_ENCRYPTED).assertDoesNotExist()
    }

    // --- the trust screen inside the server screen (spec 05, Unit 3) ---

    private val spki = SpkiPin(ByteArray(SpkiPin.SIZE) { (it + 1).toByte() })
    private val selfSigned =
        CertificateEvidence(spki, selfSigned = true, notBeforeMillis = 1_700_000L, notAfterMillis = 1_800_000L)

    private fun untrusted(certificate: CertificateEvidence = selfSigned) =
        ServerResult.Failure(MediaServerError.TlsUntrusted(certificate = certificate))

    @Test
    fun aSelfSignedCertificateOpensTheTrustScreenWithDontTrustFocused() {
        server.fake.discoverResult = untrusted()
        launch()
        typeAndTest()
        compose.onNodeWithTag(TrustTags.SCREEN).assertExists()
        compose.onNodeWithTag(TrustTags.DONT_TRUST).assertIsFocused()
        assertTrue(server.fake.trustCalls.isEmpty())
    }

    @Test
    fun dontTrustGoesBackToTheServerScreenWithTheAddressKeptAndTestFocused() {
        server.fake.discoverResult = untrusted()
        launch()
        typeAndTest()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag(TrustTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithTag(ServerTags.TEST).assertIsFocused()
        assertEquals("jellyfin.example.test", viewModel.state.value.input)
        assertEquals(ServerPhase.Idle, viewModel.state.value.phase)
        assertTrue(server.fake.trustCalls.isEmpty())
    }

    @Test
    fun backOnTheTrustScreenIsDontTrust() {
        server.fake.discoverResult = untrusted()
        launch()
        typeAndTest()
        press(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag(TrustTags.SCREEN).assertDoesNotExist()
        assertEquals(ServerPhase.Idle, viewModel.state.value.phase)
        assertTrue(server.fake.trustCalls.isEmpty())
    }

    @Test
    fun trustingPinsTheCapturedSpkiAndLandsOnContinue() {
        server.fake.discoverResult = untrusted()
        server.fake.trustResult = found()
        launch()
        typeAndTest()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag(TrustTags.TRUST).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("jellyfin.example.test" to spki), server.fake.trustCalls)
        compose.onNodeWithTag(ServerTags.CONTINUE).assertIsFocused()
        compose.onNodeWithTag(TrustTags.SCREEN).assertDoesNotExist()
    }

    @Test
    fun aCaChainedCertificateIsADeadEndWithNoTrustScreen() {
        server.fake.discoverResult = untrusted(selfSigned.copy(selfSigned = false))
        launch()
        typeAndTest()
        compose.onNodeWithTag(TrustTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithTag(ServerTags.ERROR_PANEL).assertExists()
        compose.onNodeWithTag(ServerTags.EDIT_ADDRESS).assertIsFocused()
    }

    @Test
    fun aHandshakeFailureHasNoTrustOption() {
        server.fake.discoverResult = ServerResult.Failure(MediaServerError.TlsHandshakeFailed())
        launch()
        typeAndTest()
        compose.onNodeWithTag(TrustTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithTag(ServerTags.ERROR_PANEL).assertExists()
    }

    @Test
    fun backFromTheRootScreenExitsTheApp() {
        launch()
        press(KeyEvent.KEYCODE_BACK)
        // The activity may already be destroyed by the time we look, so ask the scenario, not the activity.
        val scenario = compose.activityRule.scenario
        val deadline = System.currentTimeMillis() + 5_000
        while (scenario.state != Lifecycle.State.DESTROYED && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }
}
