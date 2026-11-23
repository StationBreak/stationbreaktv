// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import tv.stationbreak.core.domain.SyncNowState
import tv.stationbreak.core.domain.hours.AudienceLevel
import tv.stationbreak.core.domain.hours.HoursEdit
import tv.stationbreak.core.domain.hours.HoursPeriod
import tv.stationbreak.core.domain.hours.HoursSchedule
import tv.stationbreak.core.ui.GuideColors
import tv.stationbreak.core.ui.ProvideGuideColors

/**
 * The Hours of operation screen (#789, Pro) on its own, without the guide behind it: the Pro notice, the set-a-PIN
 * line, the week's list (an overnight period saying so, the PIN note), the editor's steppers, what a save said, and the
 * Settings row. A row's own semantics are cleared on purpose (one node says it), so rows are asserted by their
 * description, and a time is not (the device's own format writes it). Compiled in CI's emulator job; the key paths are
 * the unit tests'.
 */
@RunWith(AndroidJUnit4::class)
class GuideHoursUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val night = HoursPeriod(5, 22 * 60, 6 * 60, AudienceLevel.OffAir)
    private val morning = HoursPeriod(1, 7 * 60, 9 * 60, AudienceLevel.Kids)
    private val pro = SettingsValues(pro = true, kidsPinSet = true)

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { ProvideGuideColors(GuideColors.Dark) { content() } }
        compose.waitForIdle()
    }

    private fun description(contains: String) =
        SemanticsMatcher("description contains $contains") {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> contains in text } == true
        }

    @Test
    fun withoutPro_itIsAPlainNotice() {
        show { GuideHours(HoursScreen(), SettingsValues(pro = false, kidsPinSet = true)) }
        compose.onNodeWithTag(HoursTags.TITLE).assertTextEquals("Hours of operation")
        compose.onNodeWithTag(HoursTags.PRO_REQUIRED).assertTextEquals("Hours of operation is a Pro feature.")
        compose.onNodeWithTag(HoursTags.period(0)).assertDoesNotExist()
        compose.onNodeWithTag(HoursTags.ADD).assertDoesNotExist()
    }

    @Test
    fun withProAndNoPin_itAsksForAPinFirst() {
        show { GuideHours(HoursScreen(), SettingsValues(pro = true, kidsPinSet = false)) }
        compose.onNodeWithTag(HoursTags.NEEDS_PIN).assertExists()
        compose.onNodeWithTag(HoursTags.PRO_REQUIRED).assertDoesNotExist()
        compose.onNodeWithTag(HoursTags.ADD).assertDoesNotExist()
    }

    @Test
    fun anEmptyScheduleSaysNoHours_andTheAddRowHasTheFocus() {
        show { GuideHours(HoursScreen(), pro) }
        compose.onNodeWithTag(HoursTags.EMPTY).assertTextEquals("No hours set. The TV follows Family Mode only.")
        compose.onNodeWithTag(HoursTags.ADD).assertIsSelected().assertContentDescriptionEquals("Add a period")
        compose.onNodeWithTag(HoursTags.PIN_NOTE).assertExists()
    }

    @Test
    fun theListShowsEachPeriodWithItsLevel_andAnOvernightOneSaysSo() {
        val values = pro.copy(hours = HoursSchedule(listOf(night, morning)))
        show { GuideHours(HoursScreen(focus = 1), values) }
        // Sorted by day: Monday first, then Friday night, which ends the next day.
        compose.onNodeWithTag(HoursTags.period(0)).assertIsNotSelected().assert(description("Tots"))
        compose
            .onNodeWithTag(HoursTags.period(1))
            .assertIsSelected()
            .assert(description("the next day"))
            .assert(description("Off Air"))
        compose.onNodeWithTag(HoursTags.EMPTY).assertDoesNotExist()
        compose.onNodeWithTag(HoursTags.PIN_NOTE).assertExists()
        compose.onNodeWithTag(HoursTags.OVERLAP_NOTE).assertExists()
    }

    @Test
    fun theEditorShowsTheSteppers_withTheFocusedRowSelected() {
        val draft = HoursDraft.of(0, night).copy(field = HoursField.Level)
        show { GuideHours(HoursScreen(draft = draft), pro.copy(hours = HoursSchedule(listOf(night)))) }
        compose.onNodeWithTag(HoursTags.TITLE).assertTextEquals("Edit a period")
        compose
            .onNodeWithTag(HoursTags.field(HoursField.Day))
            .assertIsNotSelected()
            .assertContentDescriptionEquals("Day, Friday")
        compose.onNodeWithTag(HoursTags.field(HoursField.To)).assert(description("the next day"))
        compose
            .onNodeWithTag(HoursTags.field(HoursField.Level))
            .assertIsSelected()
            .assertContentDescriptionEquals("Level, Off Air")
        compose.onNodeWithTag(HoursTags.field(HoursField.Delete)).assertExists()
        compose.onNodeWithTag(HoursTags.PIN_NOTE).assertDoesNotExist()
    }

    @Test
    fun aNewPeriodHasNoDeleteRow() {
        show { GuideHours(HoursScreen(draft = HoursDraft.new()), pro) }
        compose.onNodeWithTag(HoursTags.TITLE).assertTextEquals("Add a period")
        compose.onNodeWithTag(HoursTags.field(HoursField.Save)).assertExists()
        compose.onNodeWithTag(HoursTags.field(HoursField.Delete)).assertDoesNotExist()
    }

    @Test
    fun eachSaveResultIsSaidInPlainWords() {
        val said =
            mapOf(
                HoursEdit.Saved to "Saved.",
                HoursEdit.ProRequired to "Hours of operation is a Pro feature. Nothing changed.",
                HoursEdit.NoPin to "Set a PIN first. Nothing changed.",
                HoursEdit.Unavailable to "Could not save. Nothing changed.",
            )
        val shown = mutableStateOf(HoursEdit.Saved)
        show { GuideHours(HoursScreen(notice = shown.value), pro) }
        for ((edit, words) in said) {
            compose.runOnUiThread { shown.value = edit }
            compose.waitForIdle()
            compose.onNodeWithTag(HoursTags.NOTICE).assertTextEquals(words)
        }
    }

    @Test
    fun theSettingsRowIsThere_andHasNoLockedLineWhileKidsModeIsOff() {
        show { GuideSettingsSheet(pro, SettingsRow.Hours, SyncNowState.Idle) }
        compose
            .onNodeWithTag(GuideTags.SETTINGS_HOURS)
            .assertIsSelected()
            .assertContentDescriptionEquals("Hours of operation")
        compose.onNodeWithTag(GuideTags.settingsLocked(SettingsRow.Hours)).assertDoesNotExist()
    }

    @Test
    fun theSettingsRowSaysKidsModeIsOn() {
        show { GuideSettingsSheet(pro.copy(kidsOn = true), SettingsRow.Hours, SyncNowState.Idle) }
        compose
            .onNodeWithTag(GuideTags.settingsLocked(SettingsRow.Hours))
            .assertTextEquals("Turn off Family Mode first")
    }
}
