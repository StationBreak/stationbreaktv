// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.content.res.Resources
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.ui.ProgramCopy
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.feature.live.NextProgram
import tv.stationbreak.feature.live.R
import java.time.ZoneId
import java.util.Locale

/**
 * The words of the tune banner's "how far in" text, from resources: a plain holder, so [BannerText.joined] is tested on
 * the JVM against the shipped `strings.xml` without an Android runtime.
 */
internal class BannerCopy(
    val justStarted: String,
    val minutesIn: (minutes: Int) -> String,
) {
    companion object {
        fun from(resources: Resources) =
            BannerCopy(
                justStarted = resources.getString(R.string.live_just_started),
                minutesIn = { resources.getString(R.string.live_min_in, it) },
            )
    }
}

/** What the banner says about progress, the next program and episodes (spec 14); the shared rules are [ProgramText]. */
internal object BannerText {
    /** The longest title the "Next" line shows, ellipsis included: the time after it must stay in view. */
    private const val NEXT_TITLE_MAX = 40
    private const val ELLIPSIS = "\u2026"

    /** How far through [startMs]..[endMs] the clock is at [nowMs], from 0 to 1; 0 for a slot with no length. */
    fun progress(
        startMs: Long,
        endMs: Long,
        nowMs: Long,
    ): Float {
        if (endMs <= startMs) return 0f
        return ((nowMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
    }

    /**
     * The minutes left at [nowMs] as the clock and the slot read (see [ProgramText.minutesUntil]): "1 min left" to the
     * end, never negative; counted from [startMs] before the program starts.
     */
    fun minutesLeft(
        startMs: Long,
        endMs: Long,
        nowMs: Long,
    ): Int = ProgramText.minutesUntil(endMs, maxOf(nowMs, startMs))

    /** [title] cut to [NEXT_TITLE_MAX] characters, the last one an ellipsis, when it is longer. */
    fun capTitle(title: String): String =
        when {
            title.length <= NEXT_TITLE_MAX -> title
            else -> title.take(NEXT_TITLE_MAX - 1) + ELLIPSIS
        }

    /**
     * "Next: <title> at <time>". The title is cut first: one line that ellipsizes at its end would cut the time, which
     * is the part that matters.
     */
    fun nextLine(
        next: NextProgram,
        copy: ProgramCopy,
        zone: ZoneId,
        is24h: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String = copy.next(capTitle(next.title), ProgramText.time(next.startMs, zone, is24h, locale))

    /**
     * "12 min in", or "Just started" under a minute: how far into the show the viewer joined. Null when [joinedMs] is
     * null (the show cut in at its start, or it is an "up next").
     */
    fun joined(
        joinedMs: Long?,
        copy: BannerCopy,
    ): String? {
        if (joinedMs == null) return null
        val minutes = (joinedMs / ProgramText.MINUTE_MS).toInt()
        return if (minutes < 1) copy.justStarted else copy.minutesIn(minutes)
    }

    /** The production year of a film, which stands where an episode line would; null for anything else or no year. */
    fun yearLine(info: ProgramInfo?): String? = info?.takeIf { it.kind == ScheduleItemKind.Movie }?.year?.toString()

    /** The episode line of [info] (see [ProgramText.episodeLine]); null for a movie, no series name, or no info. */
    fun episodeLine(
        info: ProgramInfo?,
        copy: ProgramCopy,
    ): String? =
        info?.let {
            ProgramText.episodeLine(
                isEpisode = it.kind == ScheduleItemKind.Episode,
                seriesName = it.seriesName,
                season = it.seasonNumber,
                episode = it.episodeNumber,
                title = it.title,
                copy = copy,
            )
        }
}
