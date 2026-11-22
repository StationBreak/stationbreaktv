// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import tv.stationbreak.core.domain.hours.AudienceLevel
import tv.stationbreak.core.domain.hours.HoursPeriod
import tv.stationbreak.core.domain.hours.HoursSchedule

// The steppers of the hours editor (#789): pure, so each is tested alone.

/** The minutes one step of From or To moves. */
internal const val HOURS_MINUTE_STEP = 15

private const val DAYS_PER_WEEK = 7
private const val MINUTES_PER_DAY = 24 * 60

/** The week's day [by] days on (1 Monday to 7 Sunday), wrapping round. */
internal fun stepDay(
    day: Int,
    by: Int,
): Int = Math.floorMod(day - 1 + by, DAYS_PER_WEEK) + 1

/** A start one step [by] on (0 up to the last step of the day), wrapping, and never [end]. */
internal fun stepStart(
    start: Int,
    end: Int,
    by: Int,
): Int = stepMinute(start, 0, MINUTES_PER_DAY - HOURS_MINUTE_STEP, by, skip = end)

/** An end one step [by] on (the first step after midnight up to the next midnight, 24:00), wrapping, never [start]. */
internal fun stepEnd(
    end: Int,
    start: Int,
    by: Int,
): Int = stepMinute(end, HOURS_MINUTE_STEP, MINUTES_PER_DAY, by, skip = start)

/** The level [by] places on, in the domain's order (Adult to Off Air), wrapping round. */
internal fun stepLevel(
    level: AudienceLevel,
    by: Int,
): AudienceLevel = AudienceLevel.entries[Math.floorMod(level.ordinal + by, AudienceLevel.entries.size)]

// The next value of the grid lo, lo + STEP, ... hi in the direction of [by], wrapping, skipping [skip]. A value off the
// grid (a stored period need not sit on it) moves to the nearest grid value in that direction.
private fun stepMinute(
    value: Int,
    lo: Int,
    hi: Int,
    by: Int,
    skip: Int,
): Int {
    val count = (hi - lo) / HOURS_MINUTE_STEP + 1
    val direction = if (by > 0) 1 else -1
    val below = Math.floorDiv(value - lo, HOURS_MINUTE_STEP)
    val first = if (direction > 0) below + 1 else -Math.floorDiv(lo - value, HOURS_MINUTE_STEP) - 1
    return generateSequence(first) { it + direction }
        .map { lo + Math.floorMod(it, count) * HOURS_MINUTE_STEP }
        .first { it != skip }
}

/** The schedule's periods in the order the screen lists them: by day, then start, then end, then level. */
internal fun HoursSchedule.sortedPeriods(): List<HoursPeriod> =
    periods.sortedWith(compareBy({ it.day }, { it.startMinute }, { it.endMinute }, { it.level }))

/** The period ends on the day after the one it starts on. */
internal val HoursPeriod.isOvernight: Boolean get() = endMinute < startMinute
