// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.hours

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The stored form of an [HoursSchedule]: JSON with a version, `{"v":1,"periods":[{"d":1,"s":1320,"e":360,"l":"Kids"}]}`
 * (`d` is ISO day 1 Monday to 7 Sunday, `s` and `e` minutes of the day, `l` an [AudienceLevel] name). [decode]
 * never throws: absent, damaged, newer-version or out-of-range input reads as [HoursSchedule.EMPTY], so a bad file
 * can only ever mean no hours.
 */
object HoursCodec {
    const val VERSION = 1

    private val json = Json

    fun encode(schedule: HoursSchedule): String = json.encodeToString(file(schedule))

    @Suppress("SwallowedException") // Anything unreadable is "no schedule" by design.
    fun decode(text: String?): HoursSchedule {
        return try {
            val file = json.decodeFromString<HoursFile>(text ?: return HoursSchedule.EMPTY)
            if (file.v == VERSION) HoursSchedule(file.periods.map(::period)) else HoursSchedule.EMPTY
        } catch (_: IllegalArgumentException) {
            // SerializationException and the period's own checks are IllegalArgumentExceptions.
            HoursSchedule.EMPTY
        }
    }
}

private fun file(schedule: HoursSchedule) = HoursFile(HoursCodec.VERSION, schedule.periods.map(::stored))

private fun stored(p: HoursPeriod) = PeriodFile(p.day, p.startMinute, p.endMinute, p.level.name)

private fun period(stored: PeriodFile) = HoursPeriod(stored.d, stored.s, stored.e, AudienceLevel.valueOf(stored.l))

@Serializable
private class HoursFile(
    val v: Int,
    val periods: List<PeriodFile>,
)

@Serializable
private class PeriodFile(
    val d: Int,
    val s: Int,
    val e: Int,
    val l: String,
)
