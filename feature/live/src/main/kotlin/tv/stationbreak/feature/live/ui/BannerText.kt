// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.content.res.Resources
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.feature.live.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The words of the banner that carry numbers, from resources. A plain holder of formatters, so the text rules in
 * [BannerText] are tested on the JVM against the shipped `strings.xml` without an Android runtime.
 */
internal class BannerCopy(
    val justStarted: String,
    val joining: (minutes: Int) -> String,
    val episodeBoth: (season: Int, episode: Int, title: String) -> String,
    val episodeSeason: (season: Int, title: String) -> String,
    val episodeNumber: (episode: Int, title: String) -> String,
) {
    companion object {
        fun from(resources: Resources) =
            BannerCopy(
                justStarted = resources.getString(R.string.live_just_started),
                joining = { resources.getString(R.string.live_joining, it) },
                episodeBoth = { s, e, t -> resources.getString(R.string.live_episode_both, s, e, t) },
                episodeSeason = { s, t -> resources.getString(R.string.live_episode_season, s, t) },
                episodeNumber = { e, t -> resources.getString(R.string.live_episode_number, e, t) },
            )
    }
}

/** What the banner says about time and episodes (spec 14, banner calls). Nothing here logs. */
internal object BannerText {
    private const val MS_PER_MINUTE = 60_000L
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

    /**
     * "joining 12 min in", or "Just started" under a minute. Null when [joinedMs] is null: the show cut in at its
     * start, or it is an "up next".
     */
    fun joined(
        joinedMs: Long?,
        copy: BannerCopy,
    ): String? {
        if (joinedMs == null) return null
        val minutes = (joinedMs / MS_PER_MINUTE).toInt()
        return if (minutes < 1) copy.justStarted else copy.joining(minutes)
    }

    /**
     * "S2 E5 · episode title" for an episode of a known series (the numbers that are known, else the title alone);
     * null for a movie and for anything without a series name.
     */
    fun episodeLine(
        info: ProgramInfo?,
        copy: BannerCopy,
    ): String? {
        if (info == null || info.kind != ScheduleItemKind.Episode || info.seriesName == null) return null
        val season = info.seasonNumber
        val episode = info.episodeNumber
        return when {
            season != null && episode != null -> copy.episodeBoth(season, episode, info.title)
            season != null -> copy.episodeSeason(season, info.title)
            episode != null -> copy.episodeNumber(episode, info.title)
            else -> info.title
        }
    }
}
