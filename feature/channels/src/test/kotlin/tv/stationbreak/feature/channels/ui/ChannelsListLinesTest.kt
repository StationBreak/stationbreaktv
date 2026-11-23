// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.feature.channels.ChannelGroup
import tv.stationbreak.feature.channels.ChannelRow

class ChannelsListLinesTest {
    private fun row(
        number: Int?,
        group: ChannelGroup = ChannelGroup.On,
    ) = ChannelRow(
        id = ChannelId("c-${group.name}-$number"),
        number = number,
        name = "Channel ${number ?: "none"}",
        group = group,
        kind = ChannelKind.Starter,
        kid = false,
        optIn = false,
        reason = null,
        hasOverride = false,
    )

    /** A short code for each line: H for a heading, B:<band> for a band flag, the number for a row. */
    private fun shape(lines: List<Line>): List<String> =
        lines.mapNotNull {
            when (it) {
                is Line.Heading -> "H:${it.group.name}"
                is Line.Band -> "B:${it.band.name}"
                is Line.Channel -> it.row.number?.toString() ?: "none"
                else -> null
            }
        }

    @Test
    fun aBandFlagOpensEachRunOfRowsInOneBand() {
        val lines = linesOf(listOf(row(1), row(33), row(34), row(66), row(67), row(99), row(100), row(650)))
        assertEquals(
            listOf(
                "H:On",
                "B:Tots",
                "1",
                "33",
                "B:Kids",
                "34",
                "66",
                "B:Teens",
                "67",
                "99",
                "B:Adults",
                "100",
                "650",
            ),
            shape(lines),
        )
    }

    @Test
    fun aRowWithNoNumberGetsNoFlagAndSitsAfterTheNumberedOnes() {
        val lines = linesOf(listOf(row(40), row(120), row(null), row(null)))
        assertEquals(listOf("H:On", "B:Kids", "40", "B:Adults", "120", "none", "none"), shape(lines))
    }

    @Test
    fun aGroupOfNumberlessRowsHasNoFlagAtAll() {
        val lines = linesOf(listOf(row(null, ChannelGroup.Off)))
        assertEquals(listOf("H:Off", "none"), shape(lines))
    }

    @Test
    fun eachGroupOpensItsOwnBandsAgainWithKeysThatDoNotClash() {
        val lines = linesOf(listOf(row(5), row(100), row(7, ChannelGroup.Shelf), row(9, ChannelGroup.Off)))
        assertEquals(
            listOf("H:On", "B:Tots", "5", "B:Adults", "100", "H:Shelf", "B:Tots", "7", "H:Off", "B:Tots", "9"),
            shape(lines),
        )
        val bandKeys = lines.filterIsInstance<Line.Band>().map { "band:${it.group}:${it.band}" }
        assertEquals(bandKeys.size, bandKeys.toSet().size)
        assertEquals("band:On:Tots", bandKeys.first())
    }

    @Test
    fun scrollingUpToTheFirstRowOfAGroupKeepsItsHeadingAndBandFlagInView() {
        val lines = linesOf(listOf(row(5), row(6), row(40)))
        val first = lines.indexOfFirst { it is Line.Channel }
        // Household, New, Heading, Band, row 5, row 6, Band, row 40
        assertEquals(2, linesAboveOf(lines, first))
        assertEquals(0, linesAboveOf(lines, first + 1))
        assertEquals(1, linesAboveOf(lines, lines.indexOfLast { it is Line.Channel }))
        assertEquals(0, linesAboveOf(lines, 0))
    }
}
