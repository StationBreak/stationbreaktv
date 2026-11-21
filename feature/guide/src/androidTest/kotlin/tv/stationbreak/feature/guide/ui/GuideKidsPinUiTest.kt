// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.ui.GuideColors
import tv.stationbreak.core.ui.ProvideGuideColors

/**
 * Kids Mode's two screens (#789) on their own, without the guide behind them: the PIN pad (dots, never digits; the
 * key under the D-pad as the selected one; the notice) and the Settings sheet with the lock on (rows that could undo
 * it are inert, with the reason under them). Compiled in CI's emulator job; the key paths are the unit tests'.
 */
@RunWith(AndroidJUnit4::class)
class GuideKidsPinUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { ProvideGuideColors(GuideColors.Dark) { content() } }
        compose.waitForIdle()
    }

    @Test
    fun thePadAsksFirst_andSaysTheKeyUnderTheDPad() {
        show { GuideKidsPin(PinEntry.start(PinPurpose.Set)) }
        compose.onNodeWithTag(GuideTags.KIDS_PIN_TITLE).assertTextEquals("Set a PIN")
        compose
            .onNodeWithTag(GuideTags.KIDS_PIN_PROMPT, useUnmergedTree = true)
            .assertTextEquals("Choose a 4-digit PIN")
        compose
            .onNodeWithTag(GuideTags.KIDS_PIN)
            .assertContentDescriptionEquals("Choose a 4-digit PIN. 5. 0 of 4 digits")
        compose.onNodeWithTag(kidsPinKeyTag(PadKey.D5)).assertIsSelected()
        compose.onNodeWithTag(kidsPinKeyTag(PadKey.D1)).assertIsNotSelected()
        compose.onNodeWithTag(GuideTags.KIDS_PIN_NOTICE).assertDoesNotExist()
    }

    @Test
    fun typedDigitsAreCountedNeverSaid() {
        val typed = PinEntry.start(PinPurpose.TurnOff).copy(digits = "73", focus = PadKey.Delete)
        show { GuideKidsPin(typed) }
        compose.onNodeWithTag(GuideTags.KIDS_PIN).assertContentDescriptionEquals("Delete. 2 of 4 digits")
        compose.onNodeWithTag(kidsPinKeyTag(PadKey.Delete)).assertIsSelected()
    }

    @Test
    fun aWrongPinSaysHowManyTriesAreLeft_andALockoutSaysHowLong() {
        val wrong = PinEntry.start(PinPurpose.TurnOff).copy(notice = PinNotice.Wrong(triesLeft = 2))
        show { GuideKidsPin(wrong) }
        compose.onNodeWithTag(GuideTags.KIDS_PIN_NOTICE).assertTextEquals("Wrong PIN. 2 tries left.")
    }

    @Test
    fun aLockoutRoundsUpToWholeMinutes() {
        val locked = PinEntry.start(PinPurpose.TurnOff).copy(notice = PinNotice.LockedOut(remainingMillis = 61_000))
        show { GuideKidsPin(locked) }
        val said = "Too many wrong PINs. Try again in 2 minutes."
        compose.onNodeWithTag(GuideTags.KIDS_PIN_NOTICE).assertTextEquals(said)
    }

    @Test
    fun withKidsModeOnTheRowsThatCouldUndoItAreInert_withTheirReason() {
        val on = SettingsValues(kidsOn = true, kidsPinSet = true)
        show { GuideSettingsSheet(on, SettingsRow.Libraries, tv.stationbreak.core.domain.SyncNowState.Idle) }
        val kidsValue = GuideTags.settingsValue(SettingsRow.KidsMode)
        compose.onNodeWithTag(kidsValue, useUnmergedTree = true).assertTextEquals("On")
        for (row in listOf(SettingsRow.Libraries, SettingsRow.SyncNow, SettingsRow.SignOut)) {
            compose
                .onNodeWithTag(GuideTags.settingsRow(row))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
            compose.onNodeWithTag(GuideTags.settingsLocked(row)).assertTextEquals("Turn off Kids Mode first")
        }
        compose.onNodeWithTag(GuideTags.SETTINGS_CHANGE_PIN).assertDoesNotExist()
        compose
            .onNodeWithTag(GuideTags.SETTINGS_APPEARANCE)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled))
    }

    @Test
    fun withKidsModeOffAndAPinSetChangePinIsThere() {
        val off = SettingsValues(kidsPinSet = true)
        show { GuideSettingsSheet(off, SettingsRow.KidsMode, tv.stationbreak.core.domain.SyncNowState.Idle) }
        val kidsValue = GuideTags.settingsValue(SettingsRow.KidsMode)
        compose.onNodeWithTag(kidsValue, useUnmergedTree = true).assertTextEquals("Off")
        compose.onNodeWithTag(GuideTags.SETTINGS_CHANGE_PIN).assertIsNotSelected()
        compose.onNodeWithTag(GuideTags.settingsLocked(SettingsRow.Libraries)).assertDoesNotExist()
    }
}
