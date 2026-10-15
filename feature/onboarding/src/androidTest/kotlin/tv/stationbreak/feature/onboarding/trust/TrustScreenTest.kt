// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.trust

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.model.CertificateEvidence
import tv.stationbreak.core.model.SpkiPin
import tv.stationbreak.core.ui.StationBreakTheme

/**
 * The trust screen on the API 25 emulator, driven with real key events (spec 05, Unit 3): Don't trust has default
 * focus, Back means don't trust, the fingerprint is the full SPKI SHA-256 in groups of four with its label, and the
 * warning comes before the fingerprint and the fingerprint before the buttons. A test certificate only. The screen is
 * a dialog, a window of its own, and a cold emulator is slow to focus it: every key waits for its node to be focused.
 */
@RunWith(AndroidJUnit4::class)
class TrustScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val pin = SpkiPin(ByteArray(SpkiPin.SIZE) { (it * 7 + 3).toByte() })
    private val certificate =
        CertificateEvidence(pin, selfSigned = true, notBeforeMillis = 1_700_000L, notAfterMillis = 1_800_000L)
    private var trusted = 0
    private var refused = 0

    private fun show(reason: TrustReason = TrustReason.Untrusted) {
        compose.setContent {
            StationBreakTheme {
                TrustScreen(
                    prompt = TrustPrompt(reason, certificate),
                    serverAddress = "jellyfin.example.test:8920",
                    onTrust = { trusted++ },
                    onDontTrust = { refused++ },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun awaitFocused(tag: String) {
        compose.waitUntil(FOCUS_TIMEOUT_MS) { runCatching { compose.onNodeWithTag(tag).assertIsFocused() }.isSuccess }
    }

    /** A key goes to the node that should have focus by then, never to whatever focus has reached so far. */
    private fun press(
        keyCode: Int,
        expectedFocus: String,
    ) {
        awaitFocused(expectedFocus)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    @Test
    fun dontTrustHasDefaultFocus() {
        show()
        awaitFocused(TrustTags.DONT_TRUST)
    }

    @Test
    fun selectAtLaunchRefusesAndNeverTrusts() {
        show()
        press(KeyEvent.KEYCODE_DPAD_CENTER, TrustTags.DONT_TRUST)
        compose.waitUntil(FOCUS_TIMEOUT_MS) { refused == 1 }
        assertEquals(0, trusted)
    }

    @Test
    fun backMeansDontTrust() {
        show()
        press(KeyEvent.KEYCODE_BACK, TrustTags.DONT_TRUST)
        compose.waitUntil(FOCUS_TIMEOUT_MS) { refused == 1 }
        assertEquals(0, trusted)
    }

    @Test
    fun theTrustButtonIsOnePressToTheRightAndSelectTrusts() {
        show()
        press(KeyEvent.KEYCODE_DPAD_RIGHT, TrustTags.DONT_TRUST)
        awaitFocused(TrustTags.TRUST)
        press(KeyEvent.KEYCODE_DPAD_CENTER, TrustTags.TRUST)
        compose.waitUntil(FOCUS_TIMEOUT_MS) { trusted == 1 }
        assertEquals(0, refused)
    }

    @Test
    fun leftFromTrustComesBackToDontTrust() {
        show()
        press(KeyEvent.KEYCODE_DPAD_RIGHT, TrustTags.DONT_TRUST)
        press(KeyEvent.KEYCODE_DPAD_LEFT, TrustTags.TRUST)
        awaitFocused(TrustTags.DONT_TRUST)
    }

    @Test
    fun theServerAddressIsOnTheLineUnderTheTitle() {
        show()
        compose.onNodeWithTag(TrustTags.SERVER_ADDRESS).assert(hasText("Server: jellyfin.example.test:8920"))
    }

    @Test
    fun theFingerprintIsTheFullSpkiInGroupsOfFourAndLabelled() {
        show()
        compose.onNodeWithText("SPKI SHA-256 fingerprint").assertExists()
        val description = Fingerprint.text(pin)
        compose.onNodeWithTag(TrustTags.FINGERPRINT).assert(hasContentDescription(description))
        // Sixteen groups of four digits, and each line of eight groups is on screen as text.
        assertEquals(16, description.split(" ").size)
        assertTrue(description.split(" ").all { it.length == 4 })
        for (line in Fingerprint.lines(pin)) compose.onNodeWithText(line).assertExists()
    }

    @Test
    fun theWarningComesFirstThenTheFingerprintThenTheButtons() {
        show()
        val warning = compose.onNodeWithTag(TrustTags.WARNING).getUnclippedBoundsInRoot()
        val fingerprint = compose.onNodeWithTag(TrustTags.FINGERPRINT).getUnclippedBoundsInRoot()
        val buttons = compose.onNodeWithTag(TrustTags.DONT_TRUST).getUnclippedBoundsInRoot()
        assertTrue("warning ${warning.top} above fingerprint ${fingerprint.top}", warning.top < fingerprint.top)
        assertTrue("fingerprint ${fingerprint.top} above buttons ${buttons.top}", fingerprint.top < buttons.top)
    }

    @Test
    fun anUntrustedCertificateSaysItIsSignedByTheServerItself() {
        show(TrustReason.Untrusted)
        compose.onNodeWithText("The server signed it itself", substring = true).assertExists()
        compose.onNodeWithText("Trust this server’s certificate?").assertExists()
        compose.onNodeWithTag(TrustTags.TRUST).assert(hasText("Trust this certificate"))
    }

    @Test
    fun aNameMismatchIsSaidPlainly() {
        show(TrustReason.HostnameMismatch)
        compose.onNodeWithText("different name than the address you typed", substring = true).assertExists()
    }

    @Test
    fun anExpiredCertificateNamesTheDateAndTheClock() {
        show(TrustReason.Expired)
        compose.onNodeWithText("This certificate expired on", substring = true).assertExists()
        compose.onNodeWithText("Check the TV’s date and time first.", substring = true).assertExists()
    }

    @Test
    fun aNotYetValidCertificateMentionsTheClockForADatePassed() {
        show(TrustReason.NotYetValid)
        compose.onNodeWithText("If that date has passed", substring = true).assertExists()
    }

    @Test
    fun aChangedCertificateSaysSoHasItsOwnButtonAndKeepsTheSameSafeDefault() {
        show(TrustReason.Changed)
        compose.onNodeWithText("This server’s certificate has changed").assertExists()
        compose.onNodeWithText("different from the one you trusted before", substring = true).assertExists()
        compose.onNodeWithText("You will need to sign in again.", substring = true).assertExists()
        compose.onNodeWithTag(TrustTags.TRUST).assert(hasText("Trust the new certificate"))
        awaitFocused(TrustTags.DONT_TRUST)
    }

    @Test
    fun theValidityDatesAreShown() {
        show()
        compose.onNodeWithTag(TrustTags.VALIDITY).assert(hasText("Valid from", substring = true))
    }

    @Test
    fun theWarningIsAPoliteLiveRegion() {
        show()
        val config = compose.onNodeWithTag(TrustTags.WARNING).fetchSemanticsNode().config
        assertTrue(SemanticsProperties.LiveRegion in config)
    }

    private companion object {
        const val FOCUS_TIMEOUT_MS = 10_000L
    }
}
