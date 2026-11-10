// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.ui.ProgramCopy
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.feature.live.NextProgram
import java.time.ZoneId
import java.util.Locale

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
