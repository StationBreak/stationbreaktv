// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelIdentity
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.builder.PlaysItem
import tv.stationbreak.core.domain.lineup.builder.PlaysKind
import tv.stationbreak.core.domain.lineup.builder.PlaysRow
import tv.stationbreak.feature.guide.CellKind
import tv.stationbreak.feature.guide.GuideCell
import tv.stationbreak.feature.guide.PanelTag

private const val MIN = 60_000L

/** The channel box's tag line, tags and On now / Next line (#777), for each kind of channel. */
class ChannelBoxTextTest {
    // The shipped words, as core:ui's and the guide's strings.xml have them.
    private val lines =
        ChannelLineCopy(
            builtIn = "Built-in",
            custom = "Custom",
            leftovers = "Everything else",
            kids = { "Kids · up to $it" },
            kidsPlain = "Kids",
            playsLeftovers = "Plays whatever nothing else does",
            playsLine = { label, values -> "$label: $values" },
            join = { so, next -> "$so, $next" },
            rowLabels = PlaysKind.entries.associateWith { it.name },
            yearsRange = { from, to -> "$from to $to" },
            neverRating = { "Anything rated above $it" },
        )

    private val onNow =
        OnNowCopy(
            onNow = { "On now: $it" },
            minutesLeft = { "$it min left" },
            next = { title, time -> "Up next: $title at $time" },
            offAir = "Off the air",
            stationBreak = "Station break",
            unavailable = "Removed",
        )

    private val builtIn =
        ChannelIdentity(ChannelKind.Starter, kid = false, ceiling = null, "Rain on the glass.", plays = null)
    private val kids =
        ChannelIdentity(ChannelKind.Starter, kid = true, ceiling = "TV-Y7", "Saturday morning, all week.", null)
    private val custom =
        ChannelIdentity(
            ChannelKind.Custom,
            kid = false,
            ceiling = "PG-13",
            personality = null,
            plays = PlaysRow(PlaysKind.Collections, listOf(PlaysItem.Name("Star Trek"), PlaysItem.Name("Stargate"))),
        )
    private val leftovers =
        ChannelIdentity(
            ChannelKind.Leftovers,
            kid = false,
            ceiling = "R",
            personality = null,
            plays = PlaysRow(PlaysKind.Never, listOf(PlaysItem.RatedAbove("R"))),
        )

    @Test
    fun aBuiltInLeadsWithItsPersonalityAndIsTaggedBuiltIn() {
        assertEquals(TagLine("Rain on the glass.", personality = true), tagLineOf(builtIn, lines))
        assertEquals(listOf(PanelTag.Plain("BUILT-IN")), channelTagsOf(builtIn, lines))
    }

    @Test
    fun aPersonalityLosesItsQuotationMarks() {
        val quoted = builtIn.copy(personality = "“Rain on the glass.” ")
        assertEquals("Rain on the glass.", tagLineOf(quoted, lines)?.text)
    }

    @Test
    fun aKidsChannelIsTaggedKidsUpToItsCeilingBeforeItsKind() {
        assertEquals(TagLine("Saturday morning, all week.", personality = true), tagLineOf(kids, lines))
        assertEquals(
            listOf(PanelTag.Plain("KIDS · UP TO TV-Y7"), PanelTag.Plain("BUILT-IN")),
            channelTagsOf(kids, lines),
        )
        val noCeiling = kids.copy(ceiling = null)
        assertEquals(listOf(PanelTag.Plain("KIDS"), PanelTag.Plain("BUILT-IN")), channelTagsOf(noCeiling, lines))
    }

    @Test
    fun aCustomChannelSaysItsFirstPlaysRowInTheSheetsWords() {
        assertEquals(TagLine("Collections: Star Trek, Stargate", personality = false), tagLineOf(custom, lines))
        assertEquals(listOf(PanelTag.Plain("CUSTOM")), channelTagsOf(custom, lines))
    }

    @Test
    fun yearsAndRatingsAreWordedAsTheSheetWordsThem() {
        val years = custom.copy(plays = PlaysRow(PlaysKind.Years, listOf(PlaysItem.Years(1990, 1999))))
        assertEquals("Years: 1990 to 1999", tagLineOf(years, lines)?.text)
        val never =
            custom.copy(
                plays = PlaysRow(PlaysKind.Never, listOf(PlaysItem.Name("Late"), PlaysItem.RatedAbove("PG"))),
            )
        assertEquals("Never: Late, Anything rated above PG", tagLineOf(never, lines)?.text)
    }

    @Test
    fun aCustomChannelWithNothingToSayHasNoTagLine() {
        assertNull(tagLineOf(custom.copy(plays = null), lines))
    }

    @Test
    fun leftoversSayWhatTheSheetSaysAndAreTaggedLeftovers() {
        assertEquals(TagLine("Plays whatever nothing else does", personality = false), tagLineOf(leftovers, lines))
        assertEquals(listOf(PanelTag.Plain("EVERYTHING ELSE")), channelTagsOf(leftovers, lines))
        val kidsLeftovers = leftovers.copy(kid = true, ceiling = "PG")
        assertEquals("Plays whatever nothing else does", tagLineOf(kidsLeftovers, lines)?.text)
        assertEquals(
            listOf(PanelTag.Plain("KIDS · UP TO PG"), PanelTag.Plain("EVERYTHING ELSE")),
            channelTagsOf(kidsLeftovers, lines),
        )
    }

    @Test
    fun anUnreadableChannelHasNoKindTag() {
        val unusable = ChannelIdentity(ChannelKind.Unusable, kid = false, ceiling = null, null, plays = null)
        assertEquals(emptyList<PanelTag>(), channelTagsOf(unusable, lines))
        assertNull(tagLineOf(unusable, lines))
    }

    private fun cell(
        startMin: Long,
        endMin: Long,
        kind: CellKind = CellKind.Program,
        title: String? = if (kind == CellKind.Program) "Show $startMin" else null,
    ) = GuideCell(startMin * MIN, endMin * MIN, kind, title, info = null, itemId = null)

    private val time: (Long) -> String = { "t${it / MIN}" }

    @Test
    fun onNowAndNextComeFromTheRowsOwnCells() {
        val cells = listOf(cell(0, 30), cell(30, 90), cell(90, 120))
        assertEquals(
            "On now: Show 30 · 48 min left · Up next: Show 90 at t90",
            onNowNextLine(cells, 42 * MIN, onNow, time)?.text,
        )
    }

    @Test
    fun aBreakOrASlateOnNowReadsAsTheGridLabelsItAndNextPassesBreaks() {
        val cells =
            listOf(
                cell(0, 30),
                cell(30, 35, CellKind.Break),
                cell(35, 60),
                cell(60, 62, CellKind.Break),
                cell(62, 120, CellKind.Slate),
            )
        assertEquals(
            "On now: Station break · 3 min left · Up next: Show 35 at t35",
            onNowNextLine(cells, 32 * MIN, onNow, time)?.text,
        )
        assertEquals(
            "On now: Show 35 · 15 min left · Up next: Off the air at t62",
            onNowNextLine(cells, 45 * MIN, onNow, time)?.text,
        )
        assertEquals("On now: Off the air · 50 min left", onNowNextLine(cells, 70 * MIN, onNow, time)?.text)
    }

    @Test
    fun anUnavailableProgramReadsUnavailableAndNothingAiringGivesNoLine() {
        val cells = listOf(cell(0, 30, CellKind.Unavailable), cell(30, 60))
        assertEquals(
            "On now: Removed · 20 min left · Up next: Show 30 at t30",
            onNowNextLine(cells, 10 * MIN, onNow, time)?.text,
        )
        assertNull(onNowNextLine(cells, 60 * MIN, onNow, time)?.text)
    }
}
