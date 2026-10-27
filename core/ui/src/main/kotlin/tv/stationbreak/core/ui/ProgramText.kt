// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import android.content.res.Resources
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The words of program text that carry numbers, from resources. A plain holder of formatters, so the rules in
 * [ProgramText] are tested on the JVM against the shipped `strings.xml` without an Android runtime.
 */
@Suppress("LongParameterList") // One formatter per sentence shape, as the spec 17 contract gives them.
class ProgramCopy(
    val episodeBoth: (season: Int, episode: Int, title: String) -> String,
    val episodeSeason: (season: Int, title: String) -> String,
    val episodeNumber: (episode: Int, title: String) -> String,
    val next: (title: String, time: String) -> String,
    val offAirUntil: (time: String) -> String,
    val startsIn: (minutes: Int) -> String,
    val minutesLeft: (minutes: Int) -> String,
) {
    companion object {
        fun from(resources: Resources) =
            ProgramCopy(
                episodeBoth = { s, e, t -> resources.getString(R.string.program_episode_both, s, e, t) },
                episodeSeason = { s, t -> resources.getString(R.string.program_episode_season, s, t) },
                episodeNumber = { e, t -> resources.getString(R.string.program_episode_number, e, t) },
                next = { t, at -> resources.getString(R.string.program_next, t, at) },
                offAirUntil = { resources.getString(R.string.program_off_air_until, it) },
                startsIn = { resources.getString(R.string.program_starts_in, it) },
                minutesLeft = { resources.getString(R.string.program_minutes_left, it) },
            )
    }
}

/** What the banner and the guide say about time and episodes. Primitives only. Nothing here logs. */
object ProgramText {
    private const val NOON_HOUR = 12
    private const val SLOT_DASH = " – "
    private const val MS_PER_MINUTE = 60_000L

    // Every guide cell's description and every clock label formats times: a formatter is made once per pattern (and so
    // per 12- or 24-hour clock) and locale, not once per call.
    private val formatters = ConcurrentHashMap<Pair<String, Locale>, DateTimeFormatter>()

    /** The formatter for [pattern] in [locale], made on first use and kept. */
    internal fun formatter(
        pattern: String,
        locale: Locale,
    ): DateTimeFormatter = formatters.getOrPut(pattern to locale) { DateTimeFormatter.ofPattern(pattern, locale) }

    /**
     * The slot in the viewer's wall-clock time, like a cable box: "8:00 – 8:30 PM", "11:30 AM – 12:00 PM", or
     * "20:00 – 20:30" on a 24-hour clock. [zone] is the device zone, so DST is honoured.
     */
    fun slot(
        startMs: Long,
        endMs: Long,
        zone: ZoneId,
        is24h: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String {
        val start = Instant.ofEpochMilli(startMs).atZone(zone)
        val end = Instant.ofEpochMilli(endMs).atZone(zone)
        if (is24h) {
            val clock = formatter("HH:mm", locale)
            return clock.format(start) + SLOT_DASH + clock.format(end)
        }
        val plain = formatter("h:mm", locale)
        val withMeridiem = formatter("h:mm a", locale)
        val sameHalf = (start.hour < NOON_HOUR) == (end.hour < NOON_HOUR)
        val first = if (sameHalf) plain.format(start) else withMeridiem.format(start)
        return first + SLOT_DASH + withMeridiem.format(end)
    }

    /**
     * [slot], with the weekday in front when the program starts on another day than [todayMs] in [zone]: "Tue 1:00 –
     * 1:30 AM". A guide cell can be tomorrow's, and a bare time would read as today's.
     */
    @Suppress("LongParameterList") // Primitives only, like slot.
    fun slotOn(
        startMs: Long,
        endMs: Long,
        todayMs: Long,
        zone: ZoneId,
        is24h: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String {
        val slot = slot(startMs, endMs, zone, is24h, locale)
        val start = Instant.ofEpochMilli(startMs).atZone(zone)
        val today = Instant.ofEpochMilli(todayMs).atZone(zone)
        return if (start.toLocalDate() == today.toLocalDate()) slot else weekday(start, locale) + " " + slot
    }

    /** The weekday and the wall-clock time, for the guide's clock and its day-crossing ticks: "Mon 8:12 PM". */
    fun dayTime(
        atMs: Long,
        zone: ZoneId,
        is24h: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String = weekday(Instant.ofEpochMilli(atMs).atZone(zone), locale) + " " + time(atMs, zone, is24h, locale)

    private fun weekday(
        at: ZonedDateTime,
        locale: Locale,
    ): String = formatter("EEE", locale).format(at)

    /** One wall-clock time: "8:30 PM", or "20:30" on a 24-hour clock. */
    fun time(
        atMs: Long,
        zone: ZoneId,
        is24h: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String {
        val pattern = if (is24h) "HH:mm" else "h:mm a"
        return formatter(pattern, locale).format(Instant.ofEpochMilli(atMs).atZone(zone))
    }

    /**
     * The minutes from [nowMs] to [atMs] as the viewer reads them: the clock's minute against the minute the slot
     * shows (both cut to the minute), at least 1 until [atMs] and 0 from then on. Back-to-back programs end on any
     * second; a program ending at 8:51:40 shows as ending at 8:51, so at 8:29 it has 22 minutes left, as on a cable
     * box, where rounding the 22 min 40 s up would say 23 (#440).
     */
    fun minutesUntil(
        atMs: Long,
        nowMs: Long,
    ): Int {
        if (atMs <= nowMs) return 0
        return maxOf(1L, atMs.floorDiv(MS_PER_MINUTE) - nowMs.floorDiv(MS_PER_MINUTE)).toInt()
    }

    /**
     * How long from [nowMs] to the clock's next minute, when [minutesUntil] next changes: the banner's progress tick
     * and the guide's minute tick both wait this long, so neither shows a minute late.
     */
    fun untilNextMinute(nowMs: Long): Long = MS_PER_MINUTE - nowMs.mod(MS_PER_MINUTE)

    /**
     * "S2 E5 · episode title" for an episode of a known series (the numbers that are known, else the title alone);
     * null for a movie and for anything without a series name.
     */
    @Suppress("LongParameterList") // Primitives only: core:ui sees neither core:domain nor core:model.
    fun episodeLine(
        isEpisode: Boolean,
        seriesName: String?,
        season: Int?,
        episode: Int?,
        title: String,
        copy: ProgramCopy,
    ): String? {
        if (!isEpisode || seriesName == null) return null
        return when {
            season != null && episode != null -> copy.episodeBoth(season, episode, title)
            season != null -> copy.episodeSeason(season, title)
            episode != null -> copy.episodeNumber(episode, title)
            else -> title
        }
    }
}
