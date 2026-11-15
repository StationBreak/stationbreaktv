// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import kotlin.test.Test
import kotlin.test.assertEquals

/** What a channel plays as rows (#777): the one list the properties sheet and the guide's channel box word. */
class PlaysRowsTest {
    private fun description(
        sources: List<DraftSource> = emptyList(),
        leaveOut: List<DraftSource> = emptyList(),
        kept: List<KeptItem> = emptyList(),
        ceiling: String? = null,
    ) = RulesDescription(
        sources,
        leaveOut,
        kept,
        Narrowing(genres = listOf("Narrowed")),
        ceiling,
        ShownOrder.Play(PlayOrder.Shuffle),
        showsCaps = false,
        seriesCap = null,
        filmCap = null,
    )

    private fun names(vararg texts: String) = texts.map { PlaysItem.Name(it) }

    @Test
    fun theRowsComeInTheSheetsOrderEachWithAnythingInIt() {
        val rows =
            playsRowsOf(
                description(
                    sources =
                        listOf(
                            DraftSource.Library("l", "Films"),
                            DraftSource.Show("s", "Stargate"),
                            DraftSource.Collection("c", "Star Trek"),
                            DraftSource.Genre("g", "Comedy"),
                            DraftSource.Tag("t", "Cosy"),
                            DraftSource.Pick("p", "Heat"),
                        ),
                    leaveOut = listOf(DraftSource.Tag("late", "Late")),
                    kept =
                        listOf(
                            KeptItem.StudioSource("Acme"),
                            KeptItem.YearsSource(1990, 1999),
                            KeptItem.StudioExclusion("Bad Films"),
                        ),
                    ceiling = "PG",
                ),
                anchors = listOf("Noir"),
            )

        assertEquals(
            listOf(
                PlaysRow(PlaysKind.Genres, names("Comedy")),
                PlaysRow(PlaysKind.Tags, names("Cosy")),
                PlaysRow(PlaysKind.Collections, names("Star Trek")),
                PlaysRow(PlaysKind.Shows, names("Stargate")),
                PlaysRow(PlaysKind.Titles, names("Heat")),
                PlaysRow(PlaysKind.Libraries, names("Films")),
                PlaysRow(PlaysKind.Studios, names("Acme")),
                PlaysRow(PlaysKind.Years, listOf(PlaysItem.Years(1990, 1999))),
                PlaysRow(PlaysKind.Always, names("Noir")),
                PlaysRow(PlaysKind.Never, names("Late", "Bad Films") + PlaysItem.RatedAbove("PG")),
            ),
            rows,
        )
    }

    @Test
    fun emptyRowsAreLeftOutAndWhatNarrowsIsNotARow() {
        val rows = playsRowsOf(description(sources = listOf(DraftSource.Genre("g", "Comedy"))))
        assertEquals(listOf(PlaysRow(PlaysKind.Genres, names("Comedy"))), rows)
        assertEquals(emptyList(), playsRowsOf(description()))
    }

    @Test
    fun aLeftoversChannelHasOnlyItsCeiling() {
        assertEquals(
            listOf(PlaysRow(PlaysKind.Never, listOf(PlaysItem.RatedAbove("PG")))),
            playsRowsOf(null, leftoversCeiling = "PG"),
        )
        assertEquals(emptyList(), playsRowsOf(null))
    }
}
