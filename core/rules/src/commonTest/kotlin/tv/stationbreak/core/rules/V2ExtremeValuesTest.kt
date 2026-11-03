// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import kotlin.test.Test
import kotlin.test.assertEquals

/** Version 2 fields at their limits write and read back unchanged, the way the builder writes them. */
class V2ExtremeValuesTest {
    private fun id(n: Int) = CanonicalId.parse(n.toString(16).padStart(32, '0'))

    private fun rules(
        sources: List<Source>,
        exclusions: List<Source>? = null,
        filters: Filters? = null,
        ordering: Ordering? = null,
    ) = ChannelRules(
        formatVersion = 2,
        name = "Extremes",
        seed = Seed(ULong.MAX_VALUE),
        sources = sources,
        exclusions = exclusions,
        maxRating = null,
        ordering = ordering,
        timing = Timing(null, false, 24, null, null, null, BroadcastDay(840, "06:00")),
        filler = null,
        filters = filters,
    )

    private fun assertRoundTrips(rules: ChannelRules) {
        val text = RulesWriter.write(rules)
        val back = readOk(text)
        assertEquals(rules, back)
        assertEquals(text, RulesWriter.write(back), "writing again gives the same text")
    }

    @Test
    fun capsAtTheirLimits() {
        for ((series, film) in listOf(1 to 1, 10 to 10, 1 to 10, 10 to 1)) {
            val caps = OrderingCaps(series, film)
            assertRoundTrips(rules(listOf(Source.Genre("Comedy")), ordering = Ordering(OrderingMode.Shuffle, caps)))
            assertRoundTrips(rules(listOf(Source.Genre("Comedy")), ordering = Ordering(OrderingMode.Rotation, caps)))
        }
    }

    @Test
    fun runtimeAndYearBoundsAtTheirLimits() {
        val extreme = Filters(null, 1, 9999, 1, 1440, FilterKind.Movie)
        assertRoundTrips(rules(listOf(Source.Genre("Comedy")), filters = extreme))
        val single = Filters(null, 1, 1, 1440, 1440, FilterKind.Episode)
        assertRoundTrips(rules(listOf(Source.Genre("Comedy")), filters = single))
    }

    @Test
    fun the256GenresAndLongSourceLists() {
        val genres = List(256) { "Genre number $it" }
        val filters = Filters(genres, null, null, null, null, null)
        val sources = List(256) { Source.Series(id(it + 1)) }
        val left = List(256) { Source.Tag("Tag $it") }

        assertRoundTrips(rules(sources, left, filters, Ordering(OrderingMode.InOrder)))
        assertRoundTrips(rules(List(256) { Source.Item(id(it + 1)) }, left, filters))
    }

    @Test
    fun aLongNameAndTheLongestGenreText() {
        val long = "é".repeat(RulesLimits.MAX_TEXT_CODE_POINTS)
        val filters = Filters(listOf(long), null, null, null, null, null)

        assertRoundTrips(rules(listOf(Source.Tag(long)), filters = filters))
    }
}
