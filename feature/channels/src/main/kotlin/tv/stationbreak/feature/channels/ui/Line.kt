// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import tv.stationbreak.core.domain.lineup.NumberBand
import tv.stationbreak.core.domain.lineup.bandOf
import tv.stationbreak.feature.channels.ChannelGroup
import tv.stationbreak.feature.channels.ChannelRow

/**
 * One line of the list: the "Use my collections" switch, the New channel row, a group's heading, the flag of a number
 * band within a group, a channel, or the Reset row after the last group. The heading and the band flag are not
 * focusable.
 */
internal sealed interface Line {
    data object Household : Line

    data object New : Line

    data class Heading(
        val group: ChannelGroup,
        val count: Int,
    ) : Line

    data class Band(
        val group: ChannelGroup,
        val band: NumberBand,
    ) : Line

    data class Channel(
        val row: ChannelRow,
    ) : Line

    data object Reset : Line
}

/**
 * The list's lines. Inside a group a [Line.Band] opens each run of rows in one number band (the rows are in number
 * order); a row with no number gets none and sits after the numbered ones, as before.
 */
internal fun linesOf(rows: List<ChannelRow>): List<Line> =
    buildList {
        add(Line.Household)
        add(Line.New)
        ChannelGroup.entries.forEach { group ->
            val inGroup = rows.filter { it.group == group }
            if (inGroup.isEmpty()) return@forEach
            add(Line.Heading(group, inGroup.size))
            var band: NumberBand? = null
            inGroup.forEach { row ->
                bandOf(row.number)?.takeIf { it != band }?.let {
                    band = it
                    add(Line.Band(group, it))
                }
                add(Line.Channel(row))
            }
        }
        add(Line.Reset)
    }

/**
 * How many lines above [index] to keep in view with it when scrolling up to it: the heading and the band flag that
 * open its run, which are not focusable and have to be seen with the first row under them.
 */
internal fun linesAboveOf(
    lines: List<Line>,
    index: Int,
): Int = (index - 1 downTo 0).takeWhile { lines[it] is Line.Heading || lines[it] is Line.Band }.size
