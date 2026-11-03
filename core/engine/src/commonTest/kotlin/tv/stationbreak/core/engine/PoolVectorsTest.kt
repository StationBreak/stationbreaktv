// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import tv.stationbreak.core.engine.VectorJson.array
import tv.stationbreak.core.engine.VectorJson.intOrNull
import tv.stationbreak.core.engine.VectorJson.obj
import tv.stationbreak.core.engine.VectorJson.objects
import tv.stationbreak.core.engine.VectorJson.string
import tv.stationbreak.core.engine.VectorJson.stringOrNull
import tv.stationbreak.core.engine.VectorJson.strings
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.rules.FilterKind
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `PoolEvaluator.evaluate` (and `GroupCollections`) against `docs/rules-format/vectors/pool.json` and `pool-v2.json`,
 * on every target. The expected IDs come from an independent reference written from the rules document alone. The files
 * leave out the fields the pool does not read (runtime, dates, season and episode numbers); the shared builders fill
 * them with valid defaults.
 */
class PoolVectorsTest {
    private val cases = VectorJson.parse(PoolVectorFiles.pool).array("cases").objects()
    private val v2Cases = VectorJson.parse(PoolVectorFiles.poolV2).array("cases").objects()

    @Test
    fun theFileHasTheCasesTheSpecAsks() {
        assertTrue(cases.size >= 40, "at least 40 cases, has ${cases.size}")
        assertEquals(cases.size, cases.map { it.string("name") }.toSet().size, "case names are unique")
    }

    @Test
    fun everyExpectedListIsSortedCanonicalIds() {
        for (case in cases) {
            val expected = case.strings("expected")
            assertEquals(expected.sorted(), expected, "sorted: ${case.string("name")}")
            for (id in expected) {
                assertTrue(CanonicalId.parseCanonicalOrNull(id) != null, "canonical '$id' in ${case.string("name")}")
            }
        }
    }

    @Test
    fun everyCaseGivesTheExpectedPool() {
        for (case in cases) assertPool(case, formatVersion = 1)
    }

    @Test
    fun theVersion2FileHasTheCasesThePlanAsks() {
        assertTrue(v2Cases.size >= 20, "at least 20 version 2 cases, has ${v2Cases.size}")
        assertEquals(v2Cases.size, v2Cases.map { it.string("name") }.toSet().size, "case names are unique")
        assertTrue(v2Cases.any { it.obj("films").isNotEmpty() }, "some case has a film-to-collection map")
    }

    // The version 2 file: the same shape, with filters, item sources and the film-to-collection map. The map is given
    // whatever the mode, so the rules read it as a rotation channel.
    @Test
    fun everyVersion2CaseGivesTheExpectedPoolAndFilmMap() {
        for (case in v2Cases) assertPool(case, formatVersion = 2)
    }

    private fun assertPool(
        case: JsonObject,
        formatVersion: Int,
    ) {
        val rules =
            rules(
                sources = case.array("sources").objects().map(::source),
                exclusions = case.array("exclusions").objects().map(::source),
                maxRating = case.stringOrNull("maxRating"),
                formatVersion = formatVersion,
                filters = (case["filters"] as? JsonObject)?.let(::filters),
                ordering = if (formatVersion == 2) Ordering(OrderingMode.Rotation) else null,
            )
        val snapshot =
            snapshot(
                items = case.array("items").objects().map(::item),
                series = case.array("series").objects().map(::series),
                collections = case.array("collections").objects().map(::collection),
                libraries = case.strings("libraries").map(::canon).toSet(),
            )
        val pool = PoolEvaluator.evaluate(rules, snapshot)
        assertEquals(case.strings("expected"), pool.items.map { it.id.value }.sorted(), case.string("name"))
        val expectedFilms = case["films"] as? JsonObject ?: return
        val films = GroupCollections.of(rules, snapshot, pool.items)
        val actual = pool.items.mapNotNull { item -> films[item.id]?.let { item.id.value to it.value } }.toMap()
        val expected = expectedFilms.mapValues { it.value.jsonPrimitive.content }
        assertEquals(expected, actual, "films: ${case.string("name")}")
    }

    private fun filters(o: JsonObject): Filters {
        val years = o["years"] as? JsonObject
        return Filters(
            genres = o["genres"]?.let { list -> list.jsonArray.map { it.jsonPrimitive.content } },
            yearFrom = years?.intOrNull("from"),
            yearTo = years?.intOrNull("to"),
            minRuntimeMinutes = null,
            maxRuntimeMinutes = null,
            kind = o.stringOrNull("kind")?.let { wire -> FilterKind.entries.single { it.wireName == wire } },
        )
    }

    private fun canon(text: String): CanonicalId = CanonicalId.parse(text)

    private fun JsonObject.canonId(name: String): CanonicalId = canon(string(name))

    private fun JsonObject.idOrNull(name: String): CanonicalId? = stringOrNull(name)?.let(::canon)

    private fun JsonObject.ids(name: String): Set<CanonicalId> = strings(name).map(::canon).toSet()

    private fun source(o: JsonObject): Source =
        when (val type = o.string("type")) {
            "library" -> Source.Library(o.canonId("id"))
            "collection" -> Source.Collection(o.canonId("id"))
            "series" -> Source.Series(o.canonId("id"))
            "item" -> Source.Item(o.canonId("id"))
            "genre" -> Source.Genre(o.string("name"))
            "tag" -> Source.Tag(o.string("name"))
            "yearRange" -> Source.YearRange(o.string("from").toInt(), o.string("to").toInt())
            else -> error("unknown source type $type")
        }

    private fun item(o: JsonObject): RuleItem =
        when (val kind = o.string("kind")) {
            "movie" -> {
                movie(
                    id = o.canonId("id"),
                    library = o.canonId("libraryId"),
                    rating = o.stringOrNull("officialRating"),
                    genres = o.strings("genres"),
                    tags = o.strings("tags"),
                    year = o.intOrNull("productionYear"),
                )
            }

            "episode" -> {
                episode(
                    id = o.canonId("id"),
                    seriesId = o.idOrNull("seriesId"),
                    seasonId = o.idOrNull("seasonId"),
                    library = o.canonId("libraryId"),
                    rating = o.stringOrNull("officialRating"),
                    genres = o.strings("genres"),
                    tags = o.strings("tags"),
                    year = o.intOrNull("productionYear"),
                )
            }

            else -> {
                error("unknown kind $kind")
            }
        }

    private fun series(o: JsonObject): RuleSeries =
        series(o.canonId("id"), o.stringOrNull("officialRating"), o.strings("genres"), o.strings("tags"))

    private fun collection(o: JsonObject): RuleCollection =
        collection(o.canonId("id"), o.ids("movies"), o.ids("episodes"), o.ids("series"), o.ids("seasons"))
}
