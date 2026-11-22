// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.annotation.StringRes
import tv.stationbreak.core.domain.hours.AudienceLevel
import tv.stationbreak.core.domain.hours.HoursEdit
import tv.stationbreak.feature.guide.R
import tv.stationbreak.core.ui.R as UiR

// The words of the hours screen (#789): what saving came to, the levels, the editor's rows and the hint row.

/** The words for what saving came to. */
@StringRes
internal fun hoursNoticeWords(edit: HoursEdit): Int =
    when (edit) {
        HoursEdit.Saved -> R.string.guide_hours_saved
        HoursEdit.ProRequired -> R.string.guide_hours_notice_pro
        HoursEdit.NoPin -> R.string.guide_hours_notice_pin
        HoursEdit.Unavailable -> R.string.guide_hours_notice_unavailable
    }

/** The words for a level. */
@StringRes
internal fun hoursLevelWords(level: AudienceLevel): Int =
    when (level) {
        AudienceLevel.Adult -> R.string.guide_hours_level_adult
        AudienceLevel.Teen -> R.string.guide_hours_level_teen
        AudienceLevel.Family -> R.string.guide_hours_level_family
        AudienceLevel.Kids -> R.string.guide_hours_level_kids
        AudienceLevel.OffAir -> R.string.guide_hours_level_off_air
    }

/** The words for an editor row's label. */
@StringRes
internal fun hoursFieldWords(field: HoursField): Int =
    when (field) {
        HoursField.Day -> R.string.guide_hours_field_day
        HoursField.From -> R.string.guide_hours_field_from
        HoursField.To -> R.string.guide_hours_field_to
        HoursField.Level -> R.string.guide_hours_field_level
        HoursField.Save -> R.string.guide_hours_field_save
        HoursField.Delete -> R.string.guide_hours_field_delete
    }

/**
 * What the keys do under the D-pad on the hours screen: Left and Right change a stepper; OK edits, adds, steps, saves
 * or deletes by the row; Back closes the list and the notices, and cancels the editor. Only Back without Pro or a PIN.
 */
internal fun hoursHintOf(
    screen: HoursScreen,
    values: SettingsValues,
): List<SettingsHintPair> {
    val draft = screen.draft
    val editing = hoursMode(values) == HoursMode.Editor
    val closes = if (draft != null && editing) R.string.guide_hint_pin_cancel else UiR.string.hint_close
    val back = SettingsHintPair(UiR.string.hint_key_back, closes)
    val ok = { action: Int -> SettingsHintPair(UiR.string.hint_key_ok, action, round = true) }
    val change =
        SettingsHintPair(
            UiR.string.hint_key_left_right,
            R.string.guide_hint_change,
            spokenKey = UiR.string.hint_key_left_right_spoken,
        )
    return when {
        !editing -> {
            listOf(back)
        }

        draft == null -> {
            val onAdd = screen.focus >= values.hours.periods.size
            listOf(ok(if (onAdd) R.string.guide_hint_hours_add else R.string.guide_hint_hours_edit), back)
        }

        draft.field == HoursField.Save -> {
            listOf(ok(R.string.guide_hint_hours_save), back)
        }

        draft.field == HoursField.Delete -> {
            listOf(ok(R.string.guide_hint_hours_delete), back)
        }

        else -> {
            listOf(change, ok(R.string.guide_hint_next), back)
        }
    }
}
