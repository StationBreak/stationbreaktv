// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

// A step through a Settings choice row's values (ChoiceShown.kt holds the values and their words).

// The choice row's value moved by [change]; the sheet as it was when the row is not a choice or nothing moved.
internal fun SettingsPanel.changed(
    values: SettingsValues,
    change: Change,
): SettingsStep {
    val same = SettingsStep(this)
    return when (row) {
        SettingsRow.Appearance -> {
            stepped(THEMES, values.theme, change)?.let { SettingsStep(this, theme = it) } ?: same
        }

        SettingsRow.ScreenFit -> {
            stepped(FITS, values.fit, change)?.let { SettingsStep(this, fit = it) } ?: same
        }

        SettingsRow.ChannelChange -> {
            stepped(CHANGES, values.channelChange, change)
                ?.let { SettingsStep(this, channelChange = it, channelChangeFrom = change.from) } ?: same
        }

        SettingsRow.VolumeLevelling -> {
            stepped(LEVELLINGS, values.levelling, change)
                ?.let { SettingsStep(this, levelling = it, levellingFrom = change.from) } ?: same
        }

        SettingsRow.Libraries, SettingsRow.SyncNow, SettingsRow.SignOut -> {
            same
        }
    }
}

// The value after [current] in [all] for [change], or null when it stays the same (Left on the first, Right on the
// last). OK goes round.
private fun <T> stepped(
    all: List<T>,
    current: T,
    change: Change,
): T? {
    val at = all.indexOf(current)
    val to =
        when (change) {
            Change.Previous -> (at - 1).coerceAtLeast(0)
            Change.Next -> (at + 1).coerceAtMost(all.lastIndex)
            Change.Cycle -> (at + 1) % all.size
        }
    return all[to].takeIf { to != at }
}
