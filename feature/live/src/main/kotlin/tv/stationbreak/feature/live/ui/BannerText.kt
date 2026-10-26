// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.content.res.Resources
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.ui.ProgramCopy
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.feature.live.R

/**
 * The words of the banner that carry numbers, from resources. A plain holder of formatters, so the text rules in
 * [BannerText] are tested on the JVM against the shipped `strings.xml` without an Android runtime. The episode
 * words are shared with the guide and live in [ProgramCopy].
 */
internal class BannerCopy(
    val justStarted: String,
    val joining: (minutes: Int) -> String,
) {
    companion object {
        fun from(resources: Resources) =
            BannerCopy(
                justStarted = resources.getString(R.string.live_just_started),
                joining = { resources.getString(R.string.live_joining, it) },
            )
    }
}

/** What the banner says about joining and episodes (spec 14, banner calls); the shared rules are [ProgramText]. */
internal object BannerText {
    private const val MS_PER_MINUTE = 60_000L

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
