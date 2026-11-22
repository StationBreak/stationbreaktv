// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import tv.stationbreak.feature.guide.R
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24

// What the hours screen's rows say (#789), apart from GuideHours.kt that draws them.

/** One row as drawn: its [tag], what a screen reader says ([description]), its [label] and what it holds ([value]). */
internal data class HoursLine(
    val tag: String,
    val description: String,
    val label: String,
    val value: String?,
)

// The list: a row for each period (its words, and its level on the right) and the Add row last.
@Composable
internal fun listLines(values: SettingsValues): List<HoursLine> {
    val time = rememberMinuteText()
    val locale = LocalConfiguration.current.locales[0]
    val periods =
        values.hours.sortedPeriods().mapIndexed { i, period ->
            val words = periodWords(period.day, period.startMinute, period.endMinute, time, locale)
            val level = stringResource(hoursLevelWords(period.level))
            HoursLine(
                HoursTags.period(i),
                stringResource(R.string.guide_hours_period_spoken, words, level),
                words,
                level,
            )
        }
    val add = stringResource(R.string.guide_hours_add)
    return periods + HoursLine(HoursTags.ADD, add, add, null)
}

// The editor's rows: the four steppers, Save, and Delete for a period that is there already.
@Composable
internal fun draftLines(draft: HoursDraft): List<HoursLine> {
    val time = rememberMinuteText()
    val locale = LocalConfiguration.current.locales[0]
    return draft.fields.map { field ->
        val label = stringResource(hoursFieldWords(field))
        val value =
            when (field) {
                HoursField.Day -> dayName(draft.day, TextStyle.FULL, locale)
                HoursField.From -> time(draft.start)
                HoursField.To -> endText(draft, time)
                HoursField.Level -> stringResource(hoursLevelWords(draft.level))
                HoursField.Save, HoursField.Delete -> null
            }
        val stepper = value?.let { stringResource(R.string.guide_hours_stepper, it) }
        val spoken = value?.let { stringResource(R.string.guide_settings_spoken, label, it) } ?: label
        HoursLine(HoursTags.field(field), spoken, label, stepper)
    }
}

// The end as the editor shows it: the time, with the day after said when the period runs overnight.
@Composable
private fun endText(
    draft: HoursDraft,
    time: (Int) -> String,
): String = if (draft.overnight) stringResource(R.string.guide_hours_end_next_day, time(draft.end)) else time(draft.end)

// A period in words: the day, then from and to, an overnight one saying its end is the next day.
@Composable
private fun periodWords(
    day: Int,
    start: Int,
    end: Int,
    time: (Int) -> String,
    locale: Locale,
): String =
    stringResource(
        if (end < start) R.string.guide_hours_period_overnight else R.string.guide_hours_period,
        dayName(day, TextStyle.SHORT, locale),
        time(start),
        time(end),
    )

private fun dayName(
    day: Int,
    style: TextStyle,
    locale: Locale,
): String = DayOfWeek.of(day).getDisplayName(style, locale)

// A minute of the day as the device's own time format writes it (the end of the day, 24:00, as midnight).
@Composable
private fun rememberMinuteText(): (Int) -> String {
    val context = LocalContext.current
    val format = remember(context) { DateFormat.getTimeFormat(context) }
    return remember(format) {
        { minute ->
            val at = minute % (HOURS_PER_DAY * MINUTES_PER_HOUR)
            val calendar =
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, at / MINUTES_PER_HOUR)
                    set(Calendar.MINUTE, at % MINUTES_PER_HOUR)
                }
            format.format(calendar.time)
        }
    }
}
