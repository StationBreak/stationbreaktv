// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.hours

import tv.stationbreak.core.domain.kids.KidsCap

/**
 * Who the TV is for at a moment (#789, ADR-0033): the four audience tiers of ADR-0028 and [OffAir]. A period of the
 * hours schedule has one, and so does the answer ([effectiveAudience]). They are declared from the least restrictive to
 * the most, [OffAir] showing nothing, and that order is how they compare.
 */
enum class AudienceLevel {
    Adult,
    Teen,
    Family,
    Kids,
    OffAir,
    ;

    /**
     * The Kids Mode cap that shows the same channels (ADR-0028: 1 to 33, 66, 99), for a consumer that filters by
     * [tv.stationbreak.core.domain.kids.KidsLockState.allows]. Null for [Adult] (nothing hidden) and [OffAir] (the
     * consumer shows the off-the-air state instead of a lineup).
     */
    val cap: KidsCap?
        get() =
            when (this) {
                Kids -> KidsCap.Kids
                Family -> KidsCap.Family
                Teen -> KidsCap.Teen
                Adult, OffAir -> null
            }

    fun moreRestrictive(other: AudienceLevel): AudienceLevel = if (other > this) other else this
}

/**
 * One stretch of one day at [level] ([day] is ISO: 1 Monday to 7 Sunday): from [startMinute] (minute of the local day,
 * 0 to 1439) up to but not including [endMinute] (1 to 1440 on the same day). An [endMinute] at or before
 * [startMinute] runs overnight into the next day: it ends at that minute of the day after [day]. A period belongs
 * to the day it starts on. Start equal to end is refused, not read as all day: two periods that meet make a whole day.
 */
data class HoursPeriod(
    val day: Int,
    val startMinute: Int,
    val endMinute: Int,
    val level: AudienceLevel,
) {
    init {
        require(day in 1..DAYS_PER_WEEK) { "day out of the week" }
        require(startMinute in 0 until MINUTES_PER_DAY) { "start out of the day" }
        require(endMinute in 0..MINUTES_PER_DAY) { "end out of the day" }
        require(endMinute != startMinute) { "empty period" }
    }

    private val overnight get() = endMinute < startMinute

    internal fun covers(
        at: Int,
        minute: Int,
    ): Boolean =
        when {
            !overnight -> at == day && minute in startMinute until endMinute
            else -> (at == day && minute >= startMinute) || (at == day % DAYS_PER_WEEK + 1 && minute < endMinute)
        }
}

/**
 * The weekly hours schedule (#789, Pro): [periods], in no order. Each TV has its own. Empty means no hours: the TV
 * behaves as it does without the feature.
 */
data class HoursSchedule(
    val periods: List<HoursPeriod> = emptyList(),
) {
    /**
     * The level the schedule sets at [minute] of the local ISO [day] (1 Monday to 7 Sunday), or null when no period
     * covers it. Periods that overlap resolve to the most restrictive of them. A pure function of its arguments: local
     * time, so a clock change (DST) never needs handling here.
     */
    fun levelAt(
        day: Int,
        minute: Int,
    ): AudienceLevel? =
        periods
            .filter { it.covers(day, minute) }
            .map { it.level }
            .reduceOrNull(AudienceLevel::moreRestrictive)

    companion object {
        val EMPTY = HoursSchedule()
    }
}

internal const val MINUTES_PER_HOUR = 60
internal const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
internal const val DAYS_PER_WEEK = 7
