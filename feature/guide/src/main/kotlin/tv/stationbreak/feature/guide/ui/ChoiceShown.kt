// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.annotation.StringRes
import tv.stationbreak.core.ui.ChannelChangeStyle
import tv.stationbreak.core.ui.ScreenFit
import tv.stationbreak.feature.guide.R

// The Settings sheet's choice rows: the values each steps through, and the words each value shows. A step through
// them is SettingsChoiceStep.kt's.

/** A choice row's value as drawn: its words, and where it stands ([index]) among the row's [count] values. */
internal data class ChoiceShown(
    @param:StringRes val value: Int,
    val index: Int,
    val count: Int,
)

// Each choice row's values, in the order Left and Right step through them.
internal val THEMES = GuideTheme.entries
internal val FITS = ScreenFit.entries
internal val CHANGES = ChannelChangeStyle.entries
internal val LEVELLINGS = listOf(false, true)
internal val CRASH_REPORTS = listOf(false, true)

/** What [row] shows on its right for [values], or null for a row that is not a choice. */
internal fun choiceShown(
    row: SettingsRow,
    values: SettingsValues,
): ChoiceShown? =
    when (row) {
        SettingsRow.Appearance -> shown(THEMES, values.theme, ::themeWords)
        SettingsRow.ScreenFit -> shown(FITS, values.fit, ::fitWords)
        SettingsRow.ChannelChange -> shown(CHANGES, values.channelChange, ::channelChangeWords)
        SettingsRow.VolumeLevelling -> shown(LEVELLINGS, values.levelling, ::levellingWords)
        SettingsRow.CrashReports -> shown(CRASH_REPORTS, values.crashReports, ::crashReportsWords)
        SettingsRow.Libraries, SettingsRow.SyncNow, SettingsRow.SignOut -> null
    }

private fun <T> shown(
    all: List<T>,
    value: T,
    words: (T) -> Int,
): ChoiceShown = ChoiceShown(words(value), all.indexOf(value), all.size)

@StringRes
private fun themeWords(theme: GuideTheme): Int =
    when (theme) {
        GuideTheme.Dark -> R.string.guide_theme_dark
        GuideTheme.Light -> R.string.guide_theme_light
    }

@StringRes
private fun fitWords(fit: ScreenFit): Int =
    when (fit) {
        ScreenFit.Safe -> R.string.guide_fit_safe
        ScreenFit.Edge -> R.string.guide_fit_edge
    }

@StringRes
private fun channelChangeWords(style: ChannelChangeStyle): Int =
    when (style) {
        ChannelChangeStyle.Stan -> R.string.guide_channel_change_card
        ChannelChangeStyle.Snow -> R.string.guide_channel_change_static
    }

@StringRes
private fun levellingWords(on: Boolean): Int = if (on) R.string.guide_levelling_on else R.string.guide_levelling_off

@StringRes
private fun crashReportsWords(on: Boolean): Int =
    when {
        on -> R.string.guide_crash_reports_on
        else -> R.string.guide_crash_reports_off
    }
