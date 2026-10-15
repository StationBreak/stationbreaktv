// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.trust

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.tv.material3.MaterialTheme
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

    private var primary = Color.Unspecified

    private fun show(reason: TrustReason = TrustReason.Untrusted) {
        compose.setContent {
            StationBreakTheme {
                primary = MaterialTheme.colorScheme.primary
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
        // TalkBack reads the digits one at a time, not as numbers (A9): the description is spelled out.
        compose.onNodeWithTag(TrustTags.FINGERPRINT).assert(hasContentDescription(Fingerprint.spoken(pin)))
        // Sixteen groups of four digits, and each line of eight groups is on screen as text.
        val description = Fingerprint.text(pin)
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
    fun theValidityLineIsDirectlyUnderTheFingerprintAndAboveItsNote() {
        show()
        val fingerprint = compose.onNodeWithTag(TrustTags.FINGERPRINT).getUnclippedBoundsInRoot()
        val validity = compose.onNodeWithTag(TrustTags.VALIDITY).getUnclippedBoundsInRoot()
        val note = compose.onNodeWithTag(TrustTags.FINGERPRINT_NOTE).getUnclippedBoundsInRoot()
        assertTrue("validity ${validity.top} under fingerprint ${fingerprint.bottom}", validity.top >= fingerprint.bottom)
        assertTrue("validity ${validity.bottom} above note ${note.top}", validity.bottom <= note.top)
    }

    @Test
    fun theDialogRootHasAPaneTitleSoTalkBackAnnouncesTheScreen() {
        show()
        val config = compose.onNodeWithTag(TrustTags.SCREEN).fetchSemanticsNode().config
        assertEquals("Trust this server’s certificate?", config[SemanticsProperties.PaneTitle])
    }

    @Test
    fun theChangedCertificatePaneTitleSaysItChanged() {
        show(TrustReason.Changed)
        val config = compose.onNodeWithTag(TrustTags.SCREEN).fetchSemanticsNode().config
        assertEquals("This server’s certificate has changed", config[SemanticsProperties.PaneTitle])
    }

    @Test
    fun dontTrustIsFocusedOnOpenAndKeepsFocusOnceTheDialogHasSettled() {
        show()
        awaitFocused(TrustTags.DONT_TRUST)
        // Focus must not move away late, as a dialog's own focus handling can on a slow device. No click, no key:
        // nothing but the dialog opening gave Don't trust its focus.
        repeat(SETTLE_CHECKS) {
            Thread.sleep(SETTLE_STEP_MS)
            compose.waitForIdle()
            compose.onNodeWithTag(TrustTags.DONT_TRUST).assertIsFocused()
        }
        assertEquals(0, refused)
        assertEquals(0, trusted)
    }

    @Test
    fun dontTrustHasThePrimaryFillAndTrustNeverDoes() {
        show()
        awaitFocused(TrustTags.DONT_TRUST)
        // Focus on Don't trust gives it the focus look, so Trust shows its own resting fill.
        assertTrue("Trust has the primary fill", !fillOf(TrustTags.TRUST).isCloseTo(primary))
        press(KeyEvent.KEYCODE_DPAD_RIGHT, TrustTags.DONT_TRUST)
        awaitFocused(TrustTags.TRUST)
        // Focus on Trust gives Don't trust its resting fill.
        assertTrue("Don't trust is not primary", fillOf(TrustTags.DONT_TRUST).isCloseTo(primary))
    }

    // The dialog is a window of its own and captureToImage can't capture one below API 28, so read the screen. The
    // dialog fills it, so a node's bounds in its root are screen pixels.
    private fun fillOf(tag: String): Int {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        // Just inside the button's left edge, on its vertical middle: the container, clear of the label.
        return screen.getPixel((bounds.left + bounds.width / PADDING_DIVISOR).toInt(), bounds.center.y.toInt())
    }

    private fun Int.isCloseTo(color: Color): Boolean {
        val expected = color.toArgb()
        fun channel(value: Int, shift: Int) = (value shr shift) and 0xFF
        return listOf(0, 8, 16).all { kotlin.math.abs(channel(this, it) - channel(expected, it)) <= COLOUR_SLACK }
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
        const val SETTLE_CHECKS = 10
        const val SETTLE_STEP_MS = 100L
        const val PADDING_DIVISOR = 10
        const val COLOUR_SLACK = 6
    }
}
