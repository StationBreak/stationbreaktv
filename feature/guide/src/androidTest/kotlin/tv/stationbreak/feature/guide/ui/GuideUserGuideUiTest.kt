// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.SyncNowState
import tv.stationbreak.core.ui.GuideColors
import tv.stationbreak.core.ui.ProvideGuideColors

/**
 * The Settings sheet's Help group and the user guide's QR screen (#1105). The sheet's rows scroll, and a scroll
 * matches merged semantics, so nothing here scrolls: a row's node exists whether or not it is on screen, and the
 * text inside it is read through the unmerged tree.
 */
@RunWith(AndroidJUnit4::class)
class GuideUserGuideUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { ProvideGuideColors(GuideColors.Dark) { content() } }
        compose.waitForIdle()
    }

    private fun flag() = compose.onNodeWithTag(GuideTags.settingsFlag(SettingsGroup.Help), useUnmergedTree = true)

    @Test
    fun theHelpGroupHasTheUserGuideRow_andItsExplainerShowsInTheWell() {
        show { GuideSettingsSheet(SettingsValues(), SettingsRow.UserGuide, SyncNowState.Idle) }
        flag().assertTextEquals("HELP")
        compose.onNodeWithTag(GuideTags.settingsRow(SettingsRow.UserGuide)).assertContentDescriptionEquals("User guide")
        compose
            .onNodeWithTag(GuideTags.SETTINGS_HELP, useUnmergedTree = true)
            .assertTextEquals("Shows a QR code for the user guide, to scan with your phone.")
        compose
            .onNodeWithTag(GuideTags.SETTINGS_SHEET)
            .assertContentDescriptionEquals("User guide, Shows a QR code for the user guide, to scan with your phone.")
    }

    @Test
    fun theRowIsInertUnderFamilyMode_andTheWellShowsTheLockedNote() {
        val on = SettingsValues(kidsOn = true, kidsPinSet = true)
        show { GuideSettingsSheet(on, SettingsRow.UserGuide, SyncNowState.Idle) }
        compose.onNodeWithTag(GuideTags.settingsRow(SettingsRow.UserGuide)).assertIsNotEnabled()
        compose
            .onNodeWithTag(GuideTags.SETTINGS_HELP, useUnmergedTree = true)
            .assertTextEquals("Turn off Family Mode first")
    }

    @Test
    fun theScreenShowsTheQrAndTheAddressAsText_andSpeaksOneThing() {
        show { GuideUserGuide() }
        compose
            .onNodeWithTag(GuideTags.USER_GUIDE_TITLE, useUnmergedTree = true)
            .assertTextEquals("User guide")
        compose.onNodeWithTag(GuideTags.USER_GUIDE_QR, useUnmergedTree = true).assertExists()
        compose
            .onNodeWithTag(GuideTags.USER_GUIDE_URL, useUnmergedTree = true)
            .assertTextEquals("stationbreak.tv/guide")
        compose
            .onNodeWithTag(GuideTags.USER_GUIDE)
            .assertContentDescriptionEquals("QR code for the user guide. The address is stationbreak.tv slash guide.")
    }
}
