// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.episode
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.movie
import tv.stationbreak.core.engine.series
import tv.stationbreak.core.engine.snapshot
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryNamesTest {
    private val none = CollectionNames(emptyMap())

    private fun named(
        item: RuleItem,
        name: String,
    ) = item.copy(name = name)

    @Test
    fun studiosAreDistinctAndSorted() {
        val items =
            listOf(
                movie(idOf(1), studios = listOf("Studio B", "studio a")),
                movie(idOf(2), studios = listOf("STUDIO B", "Other")),
                movie(idOf(3), studios = listOf(" Studio A ")),
            )
        val withSeries = snapshot(items = items, series = listOf(series(idOf(9), studios = listOf("Studio C"))))
        val names = LibraryNames(withSeries, none)
        assertEquals(listOf("Studio A", "STUDIO B", "Studio C"), names.studios(NameMatcher.Contains("studio")))
        assertEquals(listOf("STUDIO B"), names.studios(NameMatcher.Word("studio b")))
        val flipped = snapshot(items = items.reversed(), series = withSeries.series.values.toList())
        val reversed = LibraryNames(flipped, none)
        assertEquals(names.studios(NameMatcher.Contains("studio")), reversed.studios(NameMatcher.Contains("studio")))
    }

    @Test
    fun titlesGiveSeriesAndItemSources() {
        val show = series(idOf(20)).copy(name = "Show A")
        val items =
            listOf(
                named(movie(idOf(31)), "Film 1"),
                named(movie(idOf(30)), "Show A: The Film"),
                named(episode(idOf(32), idOf(20)), "Show A"),
            )
        val names = LibraryNames(snapshot(items = items, series = listOf(show)), none)
        assertEquals(
            listOf(Source.Series(idOf(20)), Source.Item(idOf(30))),
            names.titles(NameMatcher.Prefix("show a")),
        )
        assertEquals(listOf<Source>(Source.Item(idOf(31))), names.titles(NameMatcher.Exact("film 1")))
    }

    @Test
    fun collectionsAreAscending() {
        val named =
            CollectionNames(
                mapOf(idOf(7) to "Box Set 1 Collection", idOf(3) to "box set 2", idOf(5) to "Other Collection"),
            )
        val names = LibraryNames(snapshot(), named)
        assertEquals(listOf(idOf(3), idOf(7)), names.collections(NameMatcher.Prefix("Box Set")))
    }
}
