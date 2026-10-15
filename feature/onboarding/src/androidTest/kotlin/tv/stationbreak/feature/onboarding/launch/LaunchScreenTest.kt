// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.launch

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.model.CertificateEvidence
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.SpkiPin
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.onboarding.server.ErrorCopyTable
import tv.stationbreak.feature.onboarding.trust.TrustPrompt
import tv.stationbreak.feature.onboarding.trust.TrustReason
import tv.stationbreak.feature.onboarding.trust.TrustTags

/**
 * The launch screen on the API 25 emulator, driven with real key events (spec 05, Unit 4): a plain progress screen
 * while the restore runs, Retry with default focus when the server is unavailable, and the Unit 3 trust screen over it
 * for a changed certificate, with Don't trust as the default and Back meaning don't trust.
 */
@RunWith(AndroidJUnit4::class)
class LaunchScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val phase = mutableStateOf<LaunchPhase>(LaunchPhase.Routing)
    private var retried = 0
    private var trusted = 0
    private var refused = 0
    private var changed = 0

    private val pin = SpkiPin(ByteArray(SpkiPin.SIZE) { (it * 5 + 1).toByte() })
    private val certificate =
        CertificateEvidence(pin, selfSigned = true, notBeforeMillis = 1_700_000L, notAfterMillis = 1_800_000L)
    private val address = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)

    private fun show(initial: LaunchPhase) {
        phase.value = initial
        compose.setContent {
            StationBreakTheme {
                LaunchScreen(
                    phase.value,
                    onRetry = { retried++ },
                    onTrust = { trusted++ },
                    onDontTrust = { refused++ },
                    onChangeServer = { changed++ },
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

    private val unavailable: LaunchPhase =
        MediaServerError.Timeout().let { LaunchPhase.Unavailable(it, ErrorCopyTable.copyFor(it)) }

    @Test
    fun whileTheRestoreRunsOnlyProgressShowsAndNothingFromSetup() {
        show(LaunchPhase.Routing)
        compose.onNodeWithTag(LaunchTags.PROGRESS).assertExists()
        compose.onNodeWithTag(LaunchTags.ERROR_PANEL).assertDoesNotExist()
        compose.onNodeWithTag("server:field").assertDoesNotExist()
    }

    @Test
    fun unavailableShowsTheErrorWithRetryFocused() {
        show(unavailable)
        awaitFocused(LaunchTags.RETRY)
        compose.onNodeWithTag(LaunchTags.ERROR_PANEL).assertExists()
        compose.onNodeWithTag(LaunchTags.PROGRESS).assertDoesNotExist()
    }

    @Test
    fun selectOnRetryAsksAgain() {
        show(unavailable)
        press(KeyEvent.KEYCODE_DPAD_CENTER, LaunchTags.RETRY)
        compose.waitUntil(FOCUS_TIMEOUT_MS) { retried == 1 }
    }

    @Test
    fun aChangedCertificateShowsTheTrustScreenWithDontTrustFocused() {
        show(
            LaunchPhase.Trust(
                address.baseUrl,
                TrustPrompt(TrustReason.Changed, certificate),
                MediaServerError.TlsCertificateChanged(),
                address,
            ),
        )
        awaitFocused(TrustTags.DONT_TRUST)
        compose.onNodeWithTag(TrustTags.SERVER_ADDRESS).assertExists()
    }

    @Test
    fun backOnTheTrustScreenMeansDontTrust() {
        show(
            LaunchPhase.Trust(
                address.baseUrl,
                TrustPrompt(TrustReason.Changed, certificate),
                MediaServerError.TlsCertificateChanged(),
                address,
            ),
        )
        press(KeyEvent.KEYCODE_BACK, TrustTags.DONT_TRUST)
        compose.waitUntil(FOCUS_TIMEOUT_MS) { refused == 1 }
        assertEquals(0, trusted)
    }

    @Test
    fun theTrustButtonSaysTheNewCertificateAndSelectTrustsIt() {
        show(
            LaunchPhase.Trust(
                address.baseUrl,
                TrustPrompt(TrustReason.Changed, certificate),
                MediaServerError.TlsCertificateChanged(),
                address,
            ),
        )
        press(KeyEvent.KEYCODE_DPAD_RIGHT, TrustTags.DONT_TRUST)
        compose.onNodeWithTag(TrustTags.TRUST).assert(hasText("Trust the new certificate"))
        press(KeyEvent.KEYCODE_DPAD_CENTER, TrustTags.TRUST)
        compose.waitUntil(FOCUS_TIMEOUT_MS) { trusted == 1 }
        assertEquals(0, refused)
    }

    private companion object {
        const val FOCUS_TIMEOUT_MS = 5_000L
    }

    @Test
    fun changeServerIsOnePressToTheRightOfRetryAndRetryKeepsDefaultFocus() {
        show(unavailable)
        awaitFocused(LaunchTags.RETRY)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, LaunchTags.RETRY)
        awaitFocused(LaunchTags.CHANGE_SERVER)
        press(KeyEvent.KEYCODE_DPAD_CENTER, LaunchTags.CHANGE_SERVER)
        compose.waitUntil(FOCUS_TIMEOUT_MS) { changed == 1 }
        assertEquals(0, retried)
    }

    @Test
    fun theTitleSaysItCannotConnect() {
        show(unavailable)
        compose.onNodeWithText("Can’t connect to your server").assertExists()
    }
}
