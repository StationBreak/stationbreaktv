// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import tv.stationbreak.core.domain.kids.KidsCap

// A step through a Settings choice row's values (ChoiceShown.kt holds the values and their words).

// The choice row's value moved by [change]; the sheet as it was when the row is not a choice or nothing moved.
internal fun SettingsPanel.changed(
    values: SettingsValues,
    change: Change,
): SettingsStep =
    (
        pictureChanged(values, change) ?: playbackChanged(values, change) ?: privacyChanged(values, change)
            ?: kidsModeChanged(values, change) ?: kidsChanged(values, change)
    ) ?: SettingsStep(this)

// Left and Right on Family Mode step it Off, On (#1151): toward the state it is in does nothing, and toward the other
// asks for what OK would (the PIN pad, or turning it on at once); not at all before the lock has been read. OK is
// the sheet's own (kidsModeStep), so it never comes here.
private fun SettingsPanel.kidsModeChanged(
    values: SettingsValues,
    change: Change,
): SettingsStep? =
    when {
        row != SettingsRow.KidsMode || !values.kidsKnown -> null
        change == Change.Cycle || (change == Change.Next) == values.kidsOn -> SettingsStep(this)
        else -> kidsModeStep(values)
    }

// A move on the Cap row (#993, #1151): through the PIN pad while the lock is on, at once while it is off, and not at
// all before the lock has been read.
private fun SettingsPanel.kidsChanged(
    values: SettingsValues,
    change: Change,
): SettingsStep? {
    val target =
        if (row == SettingsRow.KidsCap && values.kidsKnown) stepped(KidsCap.entries, values.kidsCap, change) else null
    return when {
        target == null -> null
        values.kidsOn -> SettingsStep(copy(pin = PinEntry.start(PinPurpose.Cap, target)))
        else -> SettingsStep(this, kidsCap = target)
    }
}

private fun SettingsPanel.pictureChanged(
    values: SettingsValues,
    change: Change,
): SettingsStep? =
    when (row) {
        SettingsRow.Appearance -> stepped(THEMES, values.theme, change)?.let { SettingsStep(this, theme = it) }
        SettingsRow.ScreenFit -> stepped(FITS, values.fit, change)?.let { SettingsStep(this, fit = it) }
        else -> null
    }

private fun SettingsPanel.playbackChanged(
    values: SettingsValues,
    change: Change,
): SettingsStep? =
    when (row) {
        SettingsRow.ChannelChange -> {
            stepped(CHANGES, values.channelChange, change)
                ?.let { SettingsStep(this, channelChange = it, channelChangeFrom = change.from) }
        }

        SettingsRow.VolumeLevelling -> {
            stepped(LEVELLINGS, values.levelling, change)
                ?.let { SettingsStep(this, levelling = it, levellingFrom = change.from) }
        }

        else -> {
            null
        }
    }

private fun SettingsPanel.privacyChanged(
    values: SettingsValues,
    change: Change,
): SettingsStep? =
    if (row == SettingsRow.CrashReports) {
        stepped(CRASH_REPORTS, values.crashReports, change)
            ?.let { SettingsStep(this, crashReports = it, crashReportsFrom = change.from) }
    } else {
        null
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
