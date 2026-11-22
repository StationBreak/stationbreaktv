// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.annotation.StringRes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import tv.stationbreak.core.domain.hours.AudienceLevel
import tv.stationbreak.core.domain.hours.HoursEdit
import tv.stationbreak.core.domain.hours.HoursPeriod
import tv.stationbreak.core.domain.hours.HoursSchedule
import tv.stationbreak.core.ui.RemoteKey
import tv.stationbreak.core.ui.remoteKeyOf
import tv.stationbreak.core.ui.remoteKeyToAct
import tv.stationbreak.feature.guide.R
import tv.stationbreak.core.ui.R as UiR

// The Hours of operation screen over the Settings sheet (#789, ADR-0033): its state and its keys. Drawn by
// GuideHours.kt. The state is a value and the focus on it is part of it, as everywhere in the guide: no Compose focus
// search. The stored schedule and Pro are not held here: the screen reads them from [SettingsValues] at each key.

private const val DEFAULT_START = 20 * 60
private const val DEFAULT_END = 7 * 60

/** What the screen is for [SettingsValues]: the Pro notice, the set-a-PIN-first notice, or the editor itself. */
internal enum class HoursMode { ProRequired, NeedsPin, Editor }

/** Without Pro the notice, with Pro and no PIN the set-a-PIN line ([HoursEdit.NoPin]'s meaning), else the editor. */
internal fun hoursMode(values: SettingsValues): HoursMode =
    when {
        !values.pro -> HoursMode.ProRequired
        !values.kidsPinSet -> HoursMode.NeedsPin
        else -> HoursMode.Editor
    }

/** A row of the period editor. Day, From, To and Level step; Save and Delete are done with OK. */
internal enum class HoursField { Day, From, To, Level, Save, Delete }

private val NEW_FIELDS = listOf(HoursField.Day, HoursField.From, HoursField.To, HoursField.Level, HoursField.Save)
private val EDIT_FIELDS = NEW_FIELDS + HoursField.Delete

/**
 * A period being added ([index] null) or edited ([index] is its place in [sortedPeriods]) with the [field] under the
 * D-pad. The steppers only ever make a whole period: From never equals To (the domain refuses that), and the numbers
 * stay in their ranges, so [toPeriod] cannot throw.
 */
internal data class HoursDraft(
    val index: Int?,
    val day: Int,
    val start: Int,
    val end: Int,
    val level: AudienceLevel,
    val field: HoursField = HoursField.Day,
) {
    /** It ends on the day after it starts. */
    val overnight: Boolean get() = end < start

    val fields: List<HoursField> get() = if (index == null) NEW_FIELDS else EDIT_FIELDS

    fun toPeriod(): HoursPeriod = HoursPeriod(day, start, end, level)

    /** The field [by] rows away, stopping at the ends. */
    fun moved(by: Int): HoursDraft = copy(field = fields[(fields.indexOf(field) + by).coerceIn(0, fields.lastIndex)])

    /** The focused field's value one step [by] (-1 or 1) on, wrapping round; Save and Delete are not steppers. */
    fun stepped(by: Int): HoursDraft =
        when (field) {
            HoursField.Day -> copy(day = stepDay(day, by))
            HoursField.From -> copy(start = stepStart(start, end, by))
            HoursField.To -> copy(end = stepEnd(end, start, by))
            HoursField.Level -> copy(level = stepLevel(level, by))
            HoursField.Save, HoursField.Delete -> this
        }

    companion object {
        /** A new period: every night, Kids, which the viewer then steps to what they want. */
        fun new(): HoursDraft = HoursDraft(null, 1, DEFAULT_START, DEFAULT_END, AudienceLevel.Kids)

        fun of(
            index: Int,
            period: HoursPeriod,
        ): HoursDraft = HoursDraft(index, period.day, period.startMinute, period.endMinute, period.level)
    }
}

/**
 * The screen as it stands: the [focus] on the list (0 to the periods' count, which is the Add row), the [draft] being
 * edited if any, what the last save said ([notice]) and whether a save is awaited ([busy]).
 */
internal data class HoursScreen(
    val focus: Int = 0,
    val draft: HoursDraft? = null,
    val notice: HoursEdit? = null,
    val busy: Boolean = false,
)

/**
 * A key while the hours screen is open on [screen], read from [values] as they stand: the screen has every key of the
 * sheet's. Without Pro or a PIN only Back does anything (it closes). On the list Up and Down move between the periods
 * and the Add row and OK opens one; in the editor Up and Down move between its rows, Left and Right step a value
 * (round), OK steps it on, saves ([SettingsStep.hoursSave]) or deletes, and Back returns to the list; Back on the list
 * closes the screen, back to the sheet on its row. While a save is awaited nothing but Back is taken. Null for a key
 * that is not the sheet's (the media keys, Menu).
 */
internal fun SettingsPanel.hoursKey(
    screen: HoursScreen,
    values: SettingsValues,
    key: Key,
    type: KeyEventType,
    repeatCount: Int,
): SettingsStep? {
    if (remoteKeyOf(key) == null) return null
    val act = remoteKeyToAct(key, type, repeatCount)
    val editing = hoursMode(values) == HoursMode.Editor
    return when {
        act == null -> SettingsStep(this)
        act == RemoteKey.Back -> SettingsStep(backed(screen, editing))
        !editing || screen.busy -> SettingsStep(this)
        screen.draft != null -> draftStep(screen, screen.draft, values, act)
        else -> listStep(screen, values, act)
    }
}

// Back: the editor to the list, anything else out to the sheet.
private fun SettingsPanel.backed(
    screen: HoursScreen,
    editing: Boolean,
): SettingsPanel =
    if (editing && screen.draft != null) {
        copy(hours = screen.copy(draft = null, notice = null, busy = false))
    } else {
        copy(hours = null)
    }

private fun SettingsPanel.listStep(
    screen: HoursScreen,
    values: SettingsValues,
    act: RemoteKey,
): SettingsStep {
    val periods = values.hours.sortedPeriods()
    val focus = screen.focus.coerceIn(0, periods.size)
    val seen = screen.copy(focus = focus, notice = null)
    return when (act) {
        RemoteKey.Up -> SettingsStep(copy(hours = seen.copy(focus = (focus - 1).coerceAtLeast(0))))
        RemoteKey.Down -> SettingsStep(copy(hours = seen.copy(focus = (focus + 1).coerceAtMost(periods.size))))
        RemoteKey.Select -> SettingsStep(copy(hours = seen.copy(draft = draftAt(periods, focus))))
        else -> SettingsStep(copy(hours = seen))
    }
}

private fun draftAt(
    periods: List<HoursPeriod>,
    focus: Int,
): HoursDraft = periods.getOrNull(focus)?.let { HoursDraft.of(focus, it) } ?: HoursDraft.new()

private fun SettingsPanel.draftStep(
    screen: HoursScreen,
    draft: HoursDraft,
    values: SettingsValues,
    act: RemoteKey,
): SettingsStep {
    val seen = screen.copy(notice = null)
    return when (act) {
        RemoteKey.Up -> SettingsStep(copy(hours = seen.copy(draft = draft.moved(-1))))
        RemoteKey.Down -> SettingsStep(copy(hours = seen.copy(draft = draft.moved(1))))
        RemoteKey.Left -> SettingsStep(copy(hours = seen.copy(draft = draft.stepped(-1))))
        RemoteKey.Right -> SettingsStep(copy(hours = seen.copy(draft = draft.stepped(1))))
        RemoteKey.Select -> draftSelected(seen, draft, values)
        RemoteKey.Back -> SettingsStep(this)
    }
}

// OK in the editor: a stepper steps on, Save and Delete ask for the schedule to be saved with the change.
private fun SettingsPanel.draftSelected(
    screen: HoursScreen,
    draft: HoursDraft,
    values: SettingsValues,
): SettingsStep {
    val sorted = values.hours.sortedPeriods()
    val saving =
        when (draft.field) {
            HoursField.Save -> sorted.replacedAt(draft.index, draft.toPeriod())
            HoursField.Delete -> sorted.replacedAt(draft.index, null)
            else -> null
        }
    return if (saving == null) {
        SettingsStep(copy(hours = screen.copy(draft = draft.stepped(1))))
    } else {
        SettingsStep(copy(hours = screen.copy(busy = true)), hoursSave = HoursSchedule(saving))
    }
}

// The periods with the one at [index] replaced by [period], or removed when [period] is null; added when [index] is
// null (or no longer there).
private fun List<HoursPeriod>.replacedAt(
    index: Int?,
    period: HoursPeriod?,
): List<HoursPeriod> =
    when {
        index == null || index !in indices -> if (period == null) this else this + period
        period == null -> filterIndexed { i, _ -> i != index }
        else -> mapIndexed { i, old -> if (i == index) period else old }
    }

/**
 * The save came back as [edit]: [HoursEdit.Saved] returns to the list, the rest stay in the editor with what went
 * wrong said. Ignored when no save is awaited (the screen was left meanwhile).
 */
internal fun SettingsPanel.hoursSaved(edit: HoursEdit): SettingsPanel {
    val open = hours?.takeIf { it.busy } ?: return this
    val next =
        if (edit == HoursEdit.Saved) {
            open.copy(draft = null, busy = false, notice = edit)
        } else {
            open.copy(busy = false, notice = edit)
        }
    return copy(hours = next)
}
