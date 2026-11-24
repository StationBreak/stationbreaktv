// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.SyncNowState
import tv.stationbreak.core.domain.kids.KidsCap
import tv.stationbreak.core.ui.GuideColors
import tv.stationbreak.core.ui.ProvideGuideColors

/**
 * The Settings sheet's help well (#1105, #1102) and the cap line on the cap pad. The well is hidden from TalkBack, so
 * its text is read through the unmerged tree; what TalkBack says is the sheet's description. The well is pinned, so
 * nothing here scrolls to a row (a scroll matches merged semantics: scroll to a row's tag if a test must).
 */
@RunWith(AndroidJUnit4::class)
class GuideSettingsHelpUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { ProvideGuideColors(GuideColors.Dark) { content() } }
        compose.waitForIdle()
    }

    private fun well() = compose.onNodeWithTag(GuideTags.SETTINGS_HELP, useUnmergedTree = true)

    @Test
    fun theWellSaysTheFocusedRowsExplainer_andTheSheetSpeaksItWithTheRow() {
        show { GuideSettingsSheet(SettingsValues(), SettingsRow.Appearance, SyncNowState.Idle) }
        well().assertTextEquals("Dark for a dim room, Light for a bright one.")
        compose
            .onNodeWithTag(GuideTags.SETTINGS_SHEET)
            .assertContentDescriptionEquals("Appearance, Dark. Dark for a dim room, Light for a bright one.")
    }

    @Test
    fun aRowWithNoValueSpeaksItsLabelAndExplainer() {
        show { GuideSettingsSheet(SettingsValues(), SettingsRow.Libraries, SyncNowState.Idle) }
        well().assertTextEquals("Choose which of your libraries make channels.")
        compose
            .onNodeWithTag(GuideTags.SETTINGS_SHEET)
            .assertContentDescriptionEquals("Libraries, Choose which of your libraries make channels.")
    }

    @Test
    fun aRowFamilyModeHasLockedShowsItsLockedNoteInTheWell() {
        val on = SettingsValues(kidsOn = true, kidsPinSet = true)
        show { GuideSettingsSheet(on, SettingsRow.Libraries, SyncNowState.Idle) }
        well().assertTextEquals("Turn off Family Mode first")
        compose
            .onNodeWithTag(GuideTags.SETTINGS_SHEET)
            .assertContentDescriptionEquals("Libraries, Turn off Family Mode first")
    }

    @Test
    fun familyModeShowsTheCapChosen_andNothingUntilTheLockIsRead() {
        val kids = SettingsValues(kidsPinSet = true, kidsCap = KidsCap.Family)
        show { GuideSettingsSheet(kids, SettingsRow.KidsMode, SyncNowState.Idle) }
        well().assertTextEquals(
            "Channels 1 to 66: Tots and Kids. Keeps this TV to those channels. " +
                "Changing it, or turning it off, needs your PIN.",
        )
    }

    @Test
    fun familyModeIsSilentUntilItsLockHasBeenRead() {
        val unread = SettingsValues(kidsOn = true, kidsKnown = false)
        show { GuideSettingsSheet(unread, SettingsRow.KidsMode, SyncNowState.Idle) }
        well().assertDoesNotExist()
    }

    @Test
    fun theCapPadSaysWhichChannelsTheCapBeingSetKeeps() {
        show { GuideKidsPin(PinEntry.start(PinPurpose.Cap, KidsCap.Teen)) }
        compose
            .onNodeWithTag(GuideTags.KIDS_PIN_CAP_LINE, useUnmergedTree = true)
            .assertTextEquals("Channels 1 to 99: Tots, Kids and Teens.")
        compose
            .onNodeWithTag(GuideTags.KIDS_PIN)
            .assertContentDescriptionEquals(
                "Channels 1 to 99: Tots, Kids and Teens. Enter your PIN. 5. 0 of 4 digits",
            )
    }

    @Test
    fun otherPadsHaveNoCapLine() {
        show { GuideKidsPin(PinEntry.start(PinPurpose.TurnOff)) }
        compose.onNodeWithTag(GuideTags.KIDS_PIN_CAP_LINE, useUnmergedTree = true).assertDoesNotExist()
    }
}
