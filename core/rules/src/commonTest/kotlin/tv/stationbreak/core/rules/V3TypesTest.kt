// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.model.OrderingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The types check what the reader checks, so the writer cannot emit what a reader refuses. */
class V3TypesTest {
    private fun rejects(build: () -> Any) {
        assertFailsWith<IllegalArgumentException> { build() }
    }

    @Suppress("LongParameterList") // A test helper with one parameter per part.
    private fun filters(
        tags: List<String>? = null,
        studios: List<String>? = null,
        notGenres: List<String>? = null,
        episodeMin: Int? = null,
        episodeMax: Int? = null,
        movieMin: Int? = null,
        movieMax: Int? = null,
    ) = Filters(
        genres = null,
        yearFrom = null,
        yearTo = null,
        minRuntimeMinutes = null,
        maxRuntimeMinutes = null,
        kind = null,
        tags = tags,
        studios = studios,
        notGenres = notGenres,
        episodeMinRuntimeMinutes = episodeMin,
        episodeMaxRuntimeMinutes = episodeMax,
        movieMinRuntimeMinutes = movieMin,
        movieMaxRuntimeMinutes = movieMax,
    )

    @Test
    fun filterListsAre1To256() {
        filters(tags = List(256) { "T$it" })
        filters(studios = List(256) { "S$it" })
        filters(notGenres = List(256) { "G$it" })
        rejects { filters(tags = emptyList()) }
        rejects { filters(studios = emptyList()) }
        rejects { filters(notGenres = emptyList()) }
        rejects { filters(tags = List(257) { "T$it" }) }
        rejects { filters(studios = List(257) { "S$it" }) }
        rejects { filters(notGenres = List(257) { "G$it" }) }
        rejects { filters(tags = listOf("")) }
        rejects { filters(studios = listOf("a".repeat(257))) }
        rejects { filters(notGenres = listOf("a\uD800b")) }
        filters(studios = listOf("a".repeat(256)))
    }

    @Test
    fun perKindRuntimeBoundsAre1To1440AndOrdered() {
        filters(episodeMin = 1, episodeMax = 1440, movieMin = 1, movieMax = 1440)
        rejects { filters(episodeMin = 0) }
        rejects { filters(episodeMax = 1441) }
        rejects { filters(movieMin = 0) }
        rejects { filters(movieMax = 1441) }
        rejects { filters(episodeMin = 30, episodeMax = 29) }
        rejects { filters(movieMin = 100, movieMax = 99) }
        filters(episodeMin = 30, episodeMax = 30)
    }

    @Test
    fun aKnownFilterFieldNameCannotAlsoBeAnExtra() {
        val tags = JsonObject(mapOf("tags" to JsonPrimitive(1)))
        rejects { filters(tags = listOf("A")).copy(extras = tags) }
        val episode = JsonObject(mapOf("episode" to JsonPrimitive(1)))
        rejects { filters(episodeMax = 30).copy(runtimeExtras = episode) }
        val inner = JsonObject(mapOf("minMinutes" to JsonPrimitive(1)))
        rejects { filters(episodeMax = 30).copy(episodeRuntimeExtras = inner) }
    }

    @Test
    fun perKindExtrasNeedTheirWindow() {
        val extra = JsonObject(mapOf("future" to JsonPrimitive(1)))
        rejects { filters(movieMax = 30).copy(episodeRuntimeExtras = extra) }
        rejects { filters(episodeMax = 30).copy(movieRuntimeExtras = extra) }
        filters(episodeMax = 30).copy(episodeRuntimeExtras = extra)
    }

    @Test
    fun repeatGapIs0To1440AndPairIs1To1440() {
        Ordering(OrderingMode.Curated, repeatGapMinutes = 0, pairUpToMinutes = 1)
        Ordering(OrderingMode.Curated, repeatGapMinutes = 1440, pairUpToMinutes = 1440)
        rejects { Ordering(OrderingMode.Curated, repeatGapMinutes = -1) }
        rejects { Ordering(OrderingMode.Curated, repeatGapMinutes = 1441) }
        rejects { Ordering(OrderingMode.Curated, pairUpToMinutes = 0) }
        rejects { Ordering(OrderingMode.Curated, pairUpToMinutes = 1441) }
        assertEquals(180, Ordering(OrderingMode.Curated).effectiveRepeatGapMinutes)
        assertEquals(35, Ordering(OrderingMode.Curated).effectivePairUpToMinutes)
    }

    @Test
    fun curatedRefusesCaps() {
        rejects { Ordering(OrderingMode.Curated, caps = OrderingCaps(3, 1)) }
        val reused = JsonObject(mapOf("repeatGapMinutes" to JsonPrimitive(5)))
        rejects { Ordering(OrderingMode.Curated, repeatGapMinutes = 5, extras = reused) }
    }

    @Test
    fun aStudioNameIs1To256CodePoints() {
        Source.Studio("a".repeat(256))
        rejects { Source.Studio("") }
        rejects { Source.Studio("a".repeat(257)) }
        rejects { Source.Studio("a\uD800b") }
        rejects { Source.Studio("Studio A", JsonObject(mapOf("name" to JsonPrimitive("x")))) }
    }
}
