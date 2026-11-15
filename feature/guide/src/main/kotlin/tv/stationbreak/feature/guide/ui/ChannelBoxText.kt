// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import tv.stationbreak.core.domain.lineup.ChannelIdentity
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.builder.PlaysItem
import tv.stationbreak.core.domain.lineup.builder.PlaysKind
import tv.stationbreak.core.domain.lineup.builder.PlaysRow
import tv.stationbreak.core.ui.ProgramCopy
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.feature.guide.CellKind
import tv.stationbreak.feature.guide.GuideCell
import tv.stationbreak.feature.guide.PanelTag
import tv.stationbreak.feature.guide.R
import tv.stationbreak.core.ui.R as UiR

// The words of the channel box's lines under the name (#777): its tag line, its tags and what is on now and next. Plain
// functions over plain holders of words, so they are tested on the JVM; the words come from core:ui, the ones the
// properties sheet uses, so the box and the sheet word a channel one way.

/** The words of the tag line and the tags, from core:ui's resources: the properties sheet's own. */
internal class ChannelLineCopy(
    val builtIn: String,
    val custom: String,
    val leftovers: String,
    val kids: (ceiling: String) -> String,
    val kidsPlain: String,
    val playsLeftovers: String,
    val playsLine: (label: String, values: String) -> String,
    val join: (so: String, next: String) -> String,
    val rowLabels: Map<PlaysKind, String>,
    val yearsRange: (from: Int, to: Int) -> String,
    val neverRating: (ceiling: String) -> String,
) {
    companion object {
        fun from(resources: Resources) =
            ChannelLineCopy(
                builtIn = resources.getString(UiR.string.channel_tag_builtin),
                custom = resources.getString(UiR.string.channel_tag_custom),
                leftovers = resources.getString(UiR.string.channel_tag_leftovers),
                kids = { resources.getString(UiR.string.channel_tag_kids, it) },
                kidsPlain = resources.getString(UiR.string.channel_tag_kids_plain),
                playsLeftovers = resources.getString(UiR.string.channel_plays_leftovers),
                playsLine = { label, values -> resources.getString(UiR.string.channel_plays_line, label, values) },
                join = { so, next -> resources.getString(UiR.string.channel_plays_join, so, next) },
                rowLabels = PlaysKind.entries.associateWith { resources.getString(it.label) },
                yearsRange = { from, to -> resources.getString(UiR.string.channel_plays_years_range, from, to) },
                neverRating = { resources.getString(UiR.string.channel_plays_never_rating, it) },
            )

        private val PlaysKind.label: Int
            get() =
                when (this) {
                    PlaysKind.Genres -> UiR.string.channel_plays_genres
                    PlaysKind.Tags -> UiR.string.channel_plays_tags
                    PlaysKind.Collections -> UiR.string.channel_plays_collections
                    PlaysKind.Shows -> UiR.string.channel_plays_shows
                    PlaysKind.Titles -> UiR.string.channel_plays_titles
                    PlaysKind.Libraries -> UiR.string.channel_plays_libraries
                    PlaysKind.Studios -> UiR.string.channel_plays_studios
                    PlaysKind.Years -> UiR.string.channel_plays_years
                    PlaysKind.Always -> UiR.string.channel_plays_always
                    PlaysKind.Never -> UiR.string.channel_plays_never
                }
    }
}

/** The tag line: [text], and whether it is the recipe's own personality (drawn as such) or words about the rules. */
internal data class TagLine(
    val text: String,
    val personality: Boolean,
)

private val QUOTES = charArrayOf('"', '\u201C', '\u201D', '\'', '\u2018', '\u2019')

/**
 * The line under the channel's name: a starter's personality, without quotation marks; for a channel with none, one
 * line in the properties sheet's words: a leftovers channel's "Plays whatever nothing else does", else the first row
 * of the sheet's "What it plays" ([ChannelIdentity.plays]) as "Label: values". Null when there is nothing to say.
 */
internal fun tagLineOf(
    identity: ChannelIdentity,
    copy: ChannelLineCopy,
): TagLine? {
    val personality =
        identity.personality
            ?.trim()
            ?.trim(*QUOTES)
            ?.trim()
    return when {
        !personality.isNullOrEmpty() -> TagLine(personality, personality = true)
        identity.kind == ChannelKind.Leftovers -> TagLine(copy.playsLeftovers, personality = false)
        else -> identity.plays?.let { TagLine(playsLine(it, copy), personality = false) }
    }
}

// "Label: a, b", in the sheet's words.
private fun playsLine(
    row: PlaysRow,
    copy: ChannelLineCopy,
): String {
    val items =
        row.items.map {
            when (it) {
                is PlaysItem.Name -> it.text
                is PlaysItem.Years -> copy.yearsRange(it.from, it.to)
                is PlaysItem.RatedAbove -> copy.neverRating(it.rating)
            }
        }
    return copy.playsLine(copy.rowLabels.getValue(row.kind), items.reduce(copy.join))
}

/**
 * The tags: "KIDS · UP TO <ceiling>" (or "KIDS" with no ceiling) for a kids channel, then the kind: "BUILT-IN",
 * "CUSTOM" or "LEFTOVERS". A channel the app cannot read has no kind tag (it is never on the guide).
 */
internal fun channelTagsOf(
    identity: ChannelIdentity,
    copy: ChannelLineCopy,
): List<PanelTag> {
    val kids =
        if (identity.kid) identity.ceiling?.let(copy.kids) ?: copy.kidsPlain else null
    val kind =
        when (identity.kind) {
            ChannelKind.Starter -> copy.builtIn
            ChannelKind.Custom -> copy.custom
            ChannelKind.Leftovers -> copy.leftovers
            ChannelKind.Unusable, ChannelKind.UnusableCustom -> null
        }
    return listOfNotNull(kids, kind).map { PanelTag.Plain(it.uppercase()) }
}

/** The words of the On now / Next line: the guide's own, and the banner's for the minutes left and the next program. */
internal class OnNowCopy(
    val onNow: (title: String) -> String,
    val minutesLeft: (minutes: Int) -> String,
    val next: (title: String, time: String) -> String,
    val offAir: String,
    val stationBreak: String,
    val unavailable: String,
) {
    companion object {
        fun from(
            resources: Resources,
            program: ProgramCopy,
        ) = OnNowCopy(
            onNow = { resources.getString(R.string.guide_channel_on_now, it) },
            minutesLeft = program.minutesLeft,
            next = program.next,
            offAir = resources.getString(R.string.guide_off_air),
            stationBreak = resources.getString(R.string.guide_station_break),
            unavailable = resources.getString(R.string.guide_unavailable),
        )
    }
}

private const val ON_NOW_SEPARATOR = " · "

/**
 * "On now: <title> · <N> min left · Next: <title> at <time>" from the row's own [cells]: the cell airing at
 * [nowMs], and the first after it that is not a station break. A slate, a break or an unavailable program reads as
 * the grid labels it ("Off air", "Station break", "Unavailable"). The Next part is left out when nothing follows in
 * the row; null when nothing airs at [nowMs]. [time] words a start time.
 */
internal fun onNowNextLine(
    cells: List<GuideCell>,
    nowMs: Long,
    copy: OnNowCopy,
    time: (Long) -> String,
): String? {
    val now = cells.firstOrNull { it.startMs <= nowMs && nowMs < it.endMs } ?: return null
    val next = cells.firstOrNull { it.startMs >= now.endMs && it.kind != CellKind.Break }
    return listOfNotNull(
        copy.onNow(gridLabel(now, copy)),
        copy.minutesLeft(ProgramText.minutesUntil(now.endMs, nowMs)),
        next?.let { copy.next(gridLabel(it, copy), time(it.startMs)) },
    ).joinToString(ON_NOW_SEPARATOR)
}

private fun gridLabel(
    cell: GuideCell,
    copy: OnNowCopy,
): String =
    when (cell.kind) {
        CellKind.Program -> cell.title?.takeIf { it.isNotBlank() } ?: copy.unavailable
        CellKind.Slate -> copy.offAir
        CellKind.Break -> copy.stationBreak
        CellKind.Unavailable -> copy.unavailable
    }

@Composable
internal fun channelLineCopy(): ChannelLineCopy {
    val resources = LocalResources.current
    return remember(resources) { ChannelLineCopy.from(resources) }
}

@Composable
internal fun onNowCopy(): OnNowCopy {
    val resources = LocalResources.current
    val program = programCopy()
    return remember(resources, program) { OnNowCopy.from(resources, program) }
}
