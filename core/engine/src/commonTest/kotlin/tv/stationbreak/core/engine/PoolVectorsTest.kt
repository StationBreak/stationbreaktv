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
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.RuntimeFilter
import tv.stationbreak.core.model.schedule.ScheduleItem
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
private const val MS_PER_MINUTE = 60_000L

class PoolVectorsTest {
    private val cases = VectorJson.parse(PoolVectorFiles.pool).array("cases").objects()
    private val v2Cases = VectorJson.parse(PoolVectorFiles.poolV2).array("cases").objects()
    private val v3Cases = VectorJson.parse(PoolVectorFiles.poolV3).array("cases").objects()
    private val v4Cases = VectorJson.parse(PoolVectorFiles.poolV4).array("cases").objects()

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

    @Test
    fun theVersion3FileHasTheCasesThePlanAsks() {
        assertTrue(v3Cases.size >= 15, "at least 15 version 3 cases, has ${v3Cases.size}")
        assertEquals(v3Cases.size, v3Cases.map { it.string("name") }.toSet().size, "case names are unique")
        assertTrue(v3Cases.any { it["expectedAfterRuntime"] != null }, "some case has a runtime window")
        assertTrue(v3Cases.any { it.objOrEmpty("franchises").isNotEmpty() }, "some case has franchises")
    }

    // The version 3 file: studio sources, the new filter parts, the per-kind runtime windows, and the franchise map.
    // `expected` leaves the runtime part out; `expectedAfterRuntime` has it applied where the epoch's pool is cut.
    @Test
    fun everyV3CaseAgreesWithTheReference() {
        for (case in v3Cases) assertPoolV3(case)
    }

    private fun assertPoolV3(case: JsonObject) {
        val name = case.string("name")
        val filters = (case["filters"] as? JsonObject)?.let(::filters)
        val sources = case.array("sources").objects().map(::source)
        val exclusions = case.array("exclusions").objects().map(::source)
        val snapshot =
            snapshot(
                items = case.array("items").objects().map(::item),
                series = case.array("series").objects().map(::series),
                collections = case.array("collections").objects().map(::collection),
                libraries = case.strings("libraries").map(::canon).toSet(),
            )
        val common = { mode: OrderingMode ->
            rules(sources, exclusions, case.stringOrNull("maxRating"), 3, filters, Ordering(mode))
        }
        val rotation = common(OrderingMode.Rotation)
        val pool = PoolEvaluator.evaluate(rotation, snapshot)
        assertEquals(case.strings("expected"), pool.items.map { it.id.value }.sorted(), name)
        val expectedFilms = case.objOrEmpty("films").mapValues { it.value.jsonPrimitive.content }
        val films = GroupCollections.of(rotation, snapshot, pool.items)
        assertEquals(expectedFilms, films.asStrings(pool.items), "films: $name")
        val curated = GroupCollections.of(common(OrderingMode.Curated), snapshot, pool.items)
        val expectedFranchises = case.objOrEmpty("franchises").mapValues { it.value.jsonPrimitive.content }
        assertEquals(expectedFranchises, curated.asStrings(pool.items), "franchises: $name")
        val afterRuntime = case["expectedAfterRuntime"] ?: return
        val window = checkNotNull(runtimeWindow(case.obj("filters").obj("runtime"))) { "$name: no runtime window" }
        val kept = pool.items.filter { window.admits(it.runtimeMs, it.kind) }
        val expectedKept = afterRuntime.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(expectedKept, kept.map { it.id.value }.sorted(), "runtime: $name")
    }

    // The version 4 file (ADR-0026): the same rules give `expected` in a file that says 4 (studio fold 2) and
    // `expectedV3` in one that says 3 (studio fold 1).
    @Test
    fun everyV4CaseAgreesWithTheReferenceUnderBothFolds() {
        assertTrue(v4Cases.size >= 8, "at least 8 version 4 cases, has ${v4Cases.size}")
        assertEquals(v4Cases.size, v4Cases.map { it.string("name") }.toSet().size, "case names are unique")
        assertTrue(v4Cases.any { it.strings("expected") != it.strings("expectedV3") }, "the folds differ somewhere")
        for (case in v4Cases) {
            val snapshot =
                snapshot(
                    items = case.array("items").objects().map(::item),
                    series = case.array("series").objects().map(::series),
                    collections = case.array("collections").objects().map(::collection),
                    libraries = case.strings("libraries").map(::canon).toSet(),
                )
            for ((version, expected) in listOf(4 to "expected", 3 to "expectedV3")) {
                val rules =
                    rules(
                        sources = case.array("sources").objects().map(::source),
                        exclusions = case.array("exclusions").objects().map(::source),
                        maxRating = null,
                        formatVersion = version,
                        filters = (case["filters"] as? JsonObject)?.let(::filters),
                    )
                val pool =
                    PoolEvaluator
                        .evaluate(rules, snapshot)
                        .items
                        .map { it.id.value }
                        .sorted()
                assertEquals(case.strings(expected), pool, "$version: ${case.string("name")}")
            }
        }
    }

    private fun JsonObject.objOrEmpty(name: String): JsonObject = this[name] as? JsonObject ?: JsonObject(emptyMap())

    private fun JsonObject.stringsOrEmpty(name: String): List<String> = if (name in this) strings(name) else emptyList()

    private fun FilmCollections.asStrings(items: List<ScheduleItem>): Map<String, String> =
        items.mapNotNull { item -> this[item.id]?.let { item.id.value to it.value } }.toMap()

    private fun runtimeWindow(o: JsonObject): RuntimeFilter? {
        fun ms(
            window: JsonObject?,
            name: String,
        ): Long? =
            window
                ?.get(name)
                ?.jsonPrimitive
                ?.content
                ?.toLong()
                ?.times(MS_PER_MINUTE)
        val episode = o["episode"] as? JsonObject
        val movie = o["movie"] as? JsonObject
        return RuntimeFilter(
            minMs = ms(o, "minMinutes"),
            maxMs = ms(o, "maxMinutes"),
            episodeMinMs = ms(episode, "minMinutes"),
            episodeMaxMs = ms(episode, "maxMinutes"),
            movieMinMs = ms(movie, "minMinutes"),
            movieMaxMs = ms(movie, "maxMinutes"),
        )
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
        val runtime = o["runtime"] as? JsonObject
        val episode = runtime?.get("episode") as? JsonObject
        val movie = runtime?.get("movie") as? JsonObject
        return Filters(
            genres = o["genres"]?.let { list -> list.jsonArray.map { it.jsonPrimitive.content } },
            yearFrom = years?.intOrNull("from"),
            yearTo = years?.intOrNull("to"),
            minRuntimeMinutes = runtime?.intOrNull("minMinutes"),
            maxRuntimeMinutes = runtime?.intOrNull("maxMinutes"),
            tags = o["tags"]?.let { list -> list.jsonArray.map { it.jsonPrimitive.content } },
            studios = o["studios"]?.let { list -> list.jsonArray.map { it.jsonPrimitive.content } },
            notGenres = o["notGenres"]?.let { list -> list.jsonArray.map { it.jsonPrimitive.content } },
            episodeMinRuntimeMinutes = episode?.intOrNull("minMinutes"),
            episodeMaxRuntimeMinutes = episode?.intOrNull("maxMinutes"),
            movieMinRuntimeMinutes = movie?.intOrNull("minMinutes"),
            movieMaxRuntimeMinutes = movie?.intOrNull("maxMinutes"),
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
            "studio" -> Source.Studio(o.string("name"))
            "yearRange" -> Source.YearRange(o.string("from").toInt(), o.string("to").toInt())
            else -> error("unknown source type $type")
        }

    // A runtimeMs (a decimal string) is given only by the runtime cases; the builders' default stands otherwise.
    private fun item(o: JsonObject): RuleItem {
        val built = itemOf(o)
        val runtimeMs = o.stringOrNull("runtimeMs")?.toLong() ?: return built
        return built.copy(item = built.item.copy(runtimeMs = runtimeMs))
    }

    private fun itemOf(o: JsonObject): RuleItem =
        when (val kind = o.string("kind")) {
            "movie" -> {
                movie(
                    id = o.canonId("id"),
                    library = o.canonId("libraryId"),
                    rating = o.stringOrNull("officialRating"),
                    genres = o.strings("genres"),
                    tags = o.strings("tags"),
                    year = o.intOrNull("productionYear"),
                    studios = o.stringsOrEmpty("studios"),
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
                    studios = o.stringsOrEmpty("studios"),
                )
            }

            else -> {
                error("unknown kind $kind")
            }
        }

    private fun series(o: JsonObject): RuleSeries =
        series(
            o.canonId("id"),
            o.stringOrNull("officialRating"),
            o.strings("genres"),
            o.strings("tags"),
            o.stringsOrEmpty("studios"),
        )

    private fun collection(o: JsonObject): RuleCollection =
        collection(
            o.canonId("id"),
            o.ids("movies"),
            o.ids("episodes"),
            o.ids("series"),
            o.ids("seasons"),
            o.stringOrNull("name"),
        )
}
