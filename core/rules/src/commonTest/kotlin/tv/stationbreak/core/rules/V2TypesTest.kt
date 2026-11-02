// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The types of rules format version 2 (docs/rules-format.md, "Format version", "Filters", "Ordering"). */
class V2TypesTest {
    private val id = CanonicalId.parse(PLACEHOLDER_ID)

    @Suppress("LongParameterList") // Test builder: one defaulted parameter per field a test varies.
    private fun rules(
        version: Int = 2,
        sources: List<Source> = listOf(Source.Library(id)),
        exclusions: List<Source>? = null,
        ordering: Ordering? = null,
        epochHours: Int? = null,
        filters: Filters? = null,
        extras: JsonObject = NO_EXTRAS,
    ) = ChannelRules(
        formatVersion = version,
        name = null,
        seed = Seed(1uL),
        sources = sources,
        exclusions = exclusions,
        maxRating = null,
        ordering = ordering,
        timing = Timing(null, null, epochHours, null, null, null, BroadcastDay(0, null)),
        filler = null,
        filters = filters,
        extras = extras,
    )

    @Suppress("LongParameterList") // Test builder: one defaulted parameter per field a test varies.
    private fun filters(
        genres: List<String>? = null,
        yearFrom: Int? = null,
        yearTo: Int? = null,
        minRuntime: Int? = null,
        maxRuntime: Int? = null,
        kind: FilterKind? = null,
    ) = Filters(genres, yearFrom, yearTo, minRuntime, maxRuntime, kind)

    @Test
    fun aVersion1FileCannotHoldAV2Feature() {
        assertFailsWith<IllegalArgumentException> { rules(version = 1, ordering = Ordering(OrderingMode.Rotation)) }
        assertFailsWith<IllegalArgumentException> { rules(version = 1, ordering = Ordering(OrderingMode.InOrder)) }
        assertFailsWith<IllegalArgumentException> { rules(version = 1, sources = listOf(Source.Item(id))) }
        assertFailsWith<IllegalArgumentException> { rules(version = 1, filters = filters()) }
    }

    @Test
    fun aVersion2FileMayHoldNoV2Feature() {
        assertEquals(2, rules().formatVersion)
        assertEquals(1, rules().requiredVersion)
    }

    @Test
    fun aFormatVersionOutsideOneToTwoCannotBeHeld() {
        assertFailsWith<IllegalArgumentException> { rules(version = 0) }
        assertFailsWith<IllegalArgumentException> { rules(version = 3) }
    }

    @Test
    fun requiredVersionIs2ForEachFeature() {
        assertEquals(2, rules(sources = listOf(Source.Item(id))).requiredVersion)
        assertEquals(2, rules(exclusions = listOf(Source.Item(id))).requiredVersion)
        assertEquals(2, rules(filters = filters()).requiredVersion)
        assertEquals(2, rules(ordering = Ordering(OrderingMode.Rotation)).requiredVersion)
        assertEquals(2, rules(ordering = Ordering(OrderingMode.InOrder)).requiredVersion)
        assertEquals(2, rules(ordering = Ordering(OrderingMode.Shuffle, OrderingCaps(null, null))).requiredVersion)
        assertEquals(1, rules(ordering = Ordering(OrderingMode.Shuffle)).requiredVersion)
        assertEquals(1, rules(version = 1, ordering = Ordering(OrderingMode.RandomInOrder)).requiredVersion)
    }

    @Test
    fun capsRangeIs1To10() {
        assertFailsWith<IllegalArgumentException> { OrderingCaps(0, null) }
        assertFailsWith<IllegalArgumentException> { OrderingCaps(11, null) }
        assertFailsWith<IllegalArgumentException> { OrderingCaps(null, 0) }
        assertFailsWith<IllegalArgumentException> { OrderingCaps(null, 11) }
        assertEquals(1, OrderingCaps(1, 10).effectiveSeries)
        assertEquals(10, OrderingCaps(10, 1).effectiveSeries)
    }

    @Test
    fun capsDefaultTo3And1() {
        assertEquals(3, OrderingCaps(null, null).effectiveSeries)
        assertEquals(1, OrderingCaps(null, null).effectiveFilm)
    }

    @Test
    fun rotationWithoutCapsHasEffectiveCaps3And1() {
        val caps = rules(ordering = Ordering(OrderingMode.Rotation)).effectiveCaps
        assertEquals(3, caps?.effectiveSeries)
        assertEquals(1, caps?.effectiveFilm)
    }

    @Test
    fun shuffleWithoutCapsHasNoEffectiveCaps() {
        assertNull(rules(ordering = Ordering(OrderingMode.Shuffle)).effectiveCaps)
        assertNull(rules().effectiveCaps)
        assertEquals(4, rules(ordering = Ordering(OrderingMode.Shuffle, OrderingCaps(4, null))).effectiveCaps?.series)
    }

    @Test
    fun otherModesIgnoreCapsSoTheyAreNotInForce() {
        for (mode in listOf(OrderingMode.Chronological, OrderingMode.RandomInOrder, OrderingMode.InOrder)) {
            assertNull(rules(ordering = Ordering(mode, OrderingCaps(2, 2))).effectiveCaps, mode.name)
        }
    }

    @Test
    fun capsWithAShortEpochAreInvalid() {
        val rotation = Ordering(OrderingMode.Rotation)
        assertFailsWith<IllegalArgumentException> { rules(ordering = rotation, epochHours = 12) }
        assertFailsWith<IllegalArgumentException> {
            rules(ordering = Ordering(OrderingMode.Shuffle, OrderingCaps(2, null)), epochHours = 12)
        }
        rules(ordering = rotation, epochHours = 24)
        rules(ordering = rotation, epochHours = null)
        rules(ordering = Ordering(OrderingMode.Chronological), epochHours = 12)
        rules(ordering = Ordering(OrderingMode.Shuffle), epochHours = 12)
    }

    @Test
    fun filtersRanges() {
        assertFailsWith<IllegalArgumentException> { filters(yearFrom = 2000, yearTo = 1999) }
        assertFailsWith<IllegalArgumentException> { filters(yearFrom = 2000) }
        assertFailsWith<IllegalArgumentException> { filters(yearTo = 2000) }
        assertFailsWith<IllegalArgumentException> { filters(yearFrom = 0, yearTo = 5) }
        assertFailsWith<IllegalArgumentException> { filters(minRuntime = 0) }
        assertFailsWith<IllegalArgumentException> { filters(minRuntime = 1441) }
        assertFailsWith<IllegalArgumentException> { filters(maxRuntime = 0) }
        assertFailsWith<IllegalArgumentException> { filters(minRuntime = 30, maxRuntime = 20) }
        assertFailsWith<IllegalArgumentException> { filters(genres = emptyList()) }
        assertFailsWith<IllegalArgumentException> { filters(genres = listOf("")) }
        assertFailsWith<IllegalArgumentException> { filters(genres = List(257) { "Genre $it" }) }
        assertTrue(filters().isEmpty)
        assertTrue(filters(minRuntime = 1, maxRuntime = 1440, yearFrom = 1, yearTo = 1).let { !it.isEmpty })
        assertTrue(!filters(kind = FilterKind.Movie).isEmpty)
    }

    @Test
    fun anItemSourceCannotBeFiller() {
        assertFailsWith<IllegalArgumentException> {
            Filler(FillerMode.PadToGrid, null, Source.Item(id))
        }
    }

    @Test
    fun aVersion1FileMayKeepFiltersAsAnExtraButAVersion2OneMayNot() {
        val extras = JsonObject(mapOf("filters" to JsonPrimitive(1)))
        assertEquals(extras, rules(version = 1, extras = extras).extras)
        assertFailsWith<IllegalArgumentException> { rules(version = 2, extras = extras) }
        val caps = JsonObject(mapOf("caps" to JsonPrimitive(1)))
        rules(version = 1, ordering = Ordering(OrderingMode.Shuffle, extras = caps))
        assertFailsWith<IllegalArgumentException> {
            rules(version = 2, ordering = Ordering(OrderingMode.Shuffle, extras = caps))
        }
    }

    @Test
    fun withVersion2DropsCollidingExtrasAndKeepsTheRest() {
        val v1 =
            rules(
                version = 1,
                ordering =
                    Ordering(
                        OrderingMode.Shuffle,
                        extras = JsonObject(mapOf("caps" to JsonPrimitive(1), "other" to JsonPrimitive(2))),
                    ),
                extras = JsonObject(mapOf("filters" to JsonPrimitive(1), "keep" to JsonPrimitive(true))),
            )
        val v2 = v1.withVersion(2)
        assertEquals(2, v2.formatVersion)
        assertEquals(setOf("keep"), v2.extras.keys)
        assertEquals(setOf("other"), v2.ordering?.extras?.keys)
        assertEquals(v1.sources, v2.sources)
        assertEquals(v1, v1.withVersion(1))
    }
}
