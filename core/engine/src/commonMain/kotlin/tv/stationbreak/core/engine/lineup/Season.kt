// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

/**
 * A yearly window of calendar days, month and day each way, both ends in it. It never wraps the year end (a window
 * across New Year would be two). February 29 is no special case: a window holds it only by holding the days around it.
 */
data class SeasonWindow(
    val startMonth: Int,
    val startDay: Int,
    val endMonth: Int,
    val endDay: Int,
) {
    init {
        require(startMonth in 1..MONTHS && endMonth in 1..MONTHS) { "The month is 1 to 12" }
        require(startDay in 1..MAX_DAY && endDay in 1..MAX_DAY) { "The day is 1 to 31" }
        require(monthDay(startMonth, startDay) <= monthDay(endMonth, endDay)) { "A window does not wrap the year end" }
    }

    /** Whether the calendar day [month]/[day] is in the window. */
    fun contains(
        month: Int,
        day: Int,
    ): Boolean = monthDay(month, day) in monthDay(startMonth, startDay)..monthDay(endMonth, endDay)

    private fun monthDay(
        month: Int,
        day: Int,
    ): Int = month * MONTH_STEP + day

    private companion object {
        const val MONTHS = 12
        const val MAX_DAY = 31
        const val MONTH_STEP = 100
    }
}

/**
 * The seasons a recipe can air in (catalog 12, #592). A seasonal recipe's pool is empty outside its window, so the
 * planner finds nothing to home on it and the channel goes to the shelf (reason Empty) like any unqualified one, and
 * comes back by itself when the window opens. The windows are the named defaults in [LineupDefaults].
 */
enum class Season(
    val window: SeasonWindow,
) {
    Christmas(LineupDefaults.CHRISTMAS_SEASON),
    Halloween(LineupDefaults.HALLOWEEN_SEASON),
    ;

    /**
     * Whether the broadcast day that starts at [dayStartMs] is in the season, by the viewer's calendar: the lineup's
     * standard offset ([utcOffsetMinutes]) gives the date of the day's start (06:00 local, so the date is the day's).
     * A pure function of the day, never a clock read, so every TV agrees.
     */
    fun contains(
        dayStartMs: Long,
        utcOffsetMinutes: Int,
    ): Boolean {
        val local = dayStartMs + utcOffsetMinutes * MS_PER_MINUTE
        val (month, day) = monthAndDayOf(local.floorDiv(MS_PER_DAY))
        return window.contains(month, day)
    }

    internal companion object {
        private const val MS_PER_MINUTE = 60_000L
        private const val MS_PER_DAY = 86_400_000L
    }
}

// The month (1 to 12) and day of the civil date [epochDay] days after 1970-01-01 (Howard Hinnant's days-to-civil), in
// the proleptic Gregorian calendar. Kept here because common code has no java.time.
@Suppress("MagicNumber")
internal fun monthAndDayOf(epochDay: Long): Pair<Int, Int> {
    val z = epochDay + 719_468
    val era = z.floorDiv(146_097L)
    val dayOfEra = z - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val shifted = (5 * dayOfYear + 2) / 153
    val day = (dayOfYear - (153 * shifted + 2) / 5 + 1).toInt()
    val month = (if (shifted < 10) shifted + 3 else shifted - 9).toInt()
    return month to day
}
