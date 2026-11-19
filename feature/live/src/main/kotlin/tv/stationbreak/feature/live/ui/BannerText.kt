// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.content.res.Resources
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.ui.ProgramCopy
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.feature.live.NextProgram
import tv.stationbreak.feature.live.R

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

/**
 * The words TalkBack speaks for a banner, from resources: a plain holder, so [BannerText.spoken] is tested on the JVM.
 * Each part is a whole string with placeholders, so a locale can order the words as it needs.
 */
internal class SpokenCopy(
    val channel: (number: String, name: String) -> String,
    val withProgram: (channel: String, title: String) -> String,
    val withMinutes: (spoken: String, minutes: Int) -> String,
) {
    companion object {
        fun from(resources: Resources) =
            SpokenCopy(
                channel = { number, name -> resources.getString(R.string.live_spoken_channel, number, name) },
                withProgram = { channel, title -> resources.getString(R.string.live_spoken_program, channel, title) },
                withMinutes = { spoken, minutes ->
                    resources.getQuantityString(R.plurals.live_spoken_minutes_left, minutes, spoken, minutes)
                },
            )
    }
}

/** What the banner says about progress, the next program and episodes (spec 14); the shared rules are [ProgramText]. */
internal object BannerText {
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

    /**
     * What TalkBack reads when a banner appears: "<number> <name>, <title>, <n> minutes left". It depends on the
     * channel and the program alone ([minutesLeft] is read once, when the banner appears, and is null on a card for
     * what comes next), so the per-minute tick never changes it and the announcement is made once per tune.
     */
    fun spoken(
        number: String,
        channelName: String,
        title: String?,
        minutesLeft: Int?,
        copy: SpokenCopy,
    ): String {
        val channel = copy.channel(number, channelName)
        val named = if (title.isNullOrBlank()) channel else copy.withProgram(channel, title)
        return if (minutesLeft == null) named else copy.withMinutes(named, minutesLeft)
    }

    /**
     * [title] whole when [fits] accepts it, else the longest start of it, with trailing spaces dropped and an ellipsis
     * added, that [fits] accepts. When not even a lone ellipsis fits, it is the ellipsis: the caller's line still ends
     * in its own ellipsis then, and there is nothing shorter to give. [fits] is asked once per halving (a binary search
     * over the title's length), which takes a longer start to be no narrower than a shorter one.
     */
    fun fitTitle(
        title: String,
        fits: (String) -> Boolean,
    ): String =
        when {
            fits(title) -> title
            title.isEmpty() || !fits(ELLIPSIS) -> ELLIPSIS
            else -> cut(title, longestKept(title, fits))
        }

    // The most characters of [title] whose cut [fits] accepts: 0 always does (the lone ellipsis, which the caller
    // checked), and the whole title never does.
    private fun longestKept(
        title: String,
        fits: (String) -> Boolean,
    ): Int {
        var low = 0
        var high = title.length - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (fits(cut(title, mid))) low = mid else high = mid - 1
        }
        return low
    }

    // The first [kept] characters of [title] (one fewer if that would split a surrogate pair), trailing spaces dropped,
    // then an ellipsis.
    private fun cut(
        title: String,
        kept: Int,
    ): String {
        val end = if (kept > 0 && title[kept - 1].isHighSurrogate()) kept - 1 else kept
        return title.take(end).trimEnd() + ELLIPSIS
    }

    /**
     * "Up next: <title> at <time>", from the one `program_next` string, with the title cut ([fitTitle]) until the whole
     * line passes [fits]: the banner measures it against the column's real width at the TV's density and font scale.
     * The title is what gives way, since one line that ellipsizes at its end would cut [time], the part that matters.
     * With no [fits] the title is whole (what a screen reader is given).
     */
    fun nextLine(
        next: NextProgram,
        time: String,
        copy: ProgramCopy,
        fits: (String) -> Boolean = { true },
    ): String = copy.next(fitTitle(next.title) { candidate -> fits(copy.next(candidate, time)) }, time)

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
