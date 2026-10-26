// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import android.content.res.Resources
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
            val clock = DateTimeFormatter.ofPattern("HH:mm", locale)
            return clock.format(start) + SLOT_DASH + clock.format(end)
        }
        val plain = DateTimeFormatter.ofPattern("h:mm", locale)
        val withMeridiem = DateTimeFormatter.ofPattern("h:mm a", locale)
        val sameHalf = (start.hour < NOON_HOUR) == (end.hour < NOON_HOUR)
        val first = if (sameHalf) plain.format(start) else withMeridiem.format(start)
        return first + SLOT_DASH + withMeridiem.format(end)
    }

    /** One wall-clock time: "8:30 PM", or "20:30" on a 24-hour clock. */
    fun time(
        atMs: Long,
        zone: ZoneId,
        is24h: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String {
        val pattern = if (is24h) "HH:mm" else "h:mm a"
        return DateTimeFormatter.ofPattern(pattern, locale).format(Instant.ofEpochMilli(atMs).atZone(zone))
    }

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
