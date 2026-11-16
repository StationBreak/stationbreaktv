// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import java.io.File

/**
 * The fidelity check of the lineup audit's exporter (tools/audit-export). The exporter writes the library snapshot the
 * audit reads from a server's answers by the acceptance rules written down in FORMAT.md. This test gives the app's own
 * code, [ScheduleInputs], [RuleItemMapper] and [RuleInputs], the rows the fixture holds (the rows a cache would hold
 * for the fixture server's answers), and holds what it makes to the file the exporter makes of the same rows: the
 * same items, series and collections, field by field, and the same count of what was left out for each reason. The
 * exporter's own test (`tools/audit-export/export_test.py`) holds its output to the same file, so both reads give one
 * snapshot. The snapshot is what the audit is a pure function of, so equal snapshots give equal audits.
 */
class AuditExportFixtureTest {
    private val fixtures = File(requireNotNull(System.getProperty("auditExport.fixtures")) { "No fixture folder" })

    private fun json(name: String): JsonObject = Json.parseToJsonElement(File(fixtures, name).readText()).jsonObject

    private fun JsonObject.text(key: String): String? = getValue(key).takeIf { it !is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.texts(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun heldRow(o: JsonObject): HeldRow =
        HeldRow(
            itemId = checkNotNull(o.text("itemId")),
            libraryId = checkNotNull(o.text("libraryId")),
            kind = checkNotNull(o.text("kind")),
            seriesId = o.text("seriesId"),
            seasonNumber = o.getValue("seasonNumber").jsonPrimitive.intOrNull,
            episodeNumber = o.getValue("episodeNumber").jsonPrimitive.intOrNull,
            runtimeMs = o.getValue("runtimeMs").jsonPrimitive.longOrNull,
            createdAtMs = o.getValue("createdAtMs").jsonPrimitive.longOrNull,
            premiereDate = o.text("premiereDate"),
            productionYear = o.getValue("productionYear").jsonPrimitive.intOrNull,
            deletedAtMs = o.getValue("deletedAtMs").jsonPrimitive.longOrNull,
            seenFromMs = o.getValue("seenFromMs").jsonPrimitive.long,
            officialRating = o.text("officialRating"),
            genres = o.texts("genres"),
            tags = o.texts("tags"),
            seasonId = o.text("seasonId"),
            studios = o.texts("studios"),
            name = o.text("name"),
        )

    private fun heldSeries(o: JsonObject): HeldSeries =
        HeldSeries(
            seriesId = checkNotNull(o.text("seriesId")),
            officialRating = o.text("officialRating"),
            genres = o.texts("genres"),
            tags = o.texts("tags"),
            studios = o.texts("studios"),
            name = o.text("name"),
        )

    private fun heldCollection(o: JsonObject): HeldCollection =
        HeldCollection(
            collectionId = checkNotNull(o.text("collectionId")),
            members =
                o.getValue("members").jsonArray.map {
                    val member = it.jsonObject
                    HeldMember(checkNotNull(member.text("memberId")), checkNotNull(member.text("kind")))
                },
            name = o.text("name"),
        )

    // The snapshot the app's own read makes of the fixture's rows, the way RoomLibraryRepository.assemble makes it.
    private fun appSnapshot(rows: JsonObject): Pair<RuleSnapshot, Map<String, Int>> {
        val read = ScheduleInputs.read(rows.getValue("rows").jsonArray.map { heldRow(it.jsonObject) })
        val mapper = RuleItemMapper()
        val items = read.accepted.map(mapper::of)
        val held = rows.getValue("series").jsonArray.map { heldSeries(it.jsonObject) }
        val collections = rows.getValue("collections").jsonArray.map { heldCollection(it.jsonObject) }
        val snapshot =
            RuleSnapshot(
                cutoffMs = rows.getValue("cutoffMs").jsonPrimitive.long,
                libraries = libraryIds(rows),
                items = items,
                series = RuleInputs.series(items, held, mapper.folds),
                collections = RuleInputs.collections(collections),
                collectionsSynced = rows.getValue("collectionsSynced").jsonPrimitive.boolean,
            )
        return snapshot to read.dropped.mapKeys { it.key.name }
    }

    private fun libraryIds(rows: JsonObject): Set<CanonicalId> {
        val ids = rows.getValue("libraries").jsonArray.map { it.jsonPrimitive.content }
        return ids.mapNotNullTo(HashSet()) { CanonicalId.parseOrNull(it) }
    }

    private fun text(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    private fun number(value: Number?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    private fun strings(values: Collection<String>): JsonElement = JsonArray(values.map(::JsonPrimitive))

    private fun ids(values: Collection<CanonicalId>): JsonElement {
        val sorted = values.map { it.value }.sorted()
        return JsonArray(sorted.map(::JsonPrimitive))
    }

    private fun itemJson(e: RuleItem): JsonObject =
        JsonObject(
            mapOf(
                "id" to text(e.item.id.value),
                "libraryId" to text(e.libraryId.value),
                "seasonId" to text(e.seasonId?.value),
                "kind" to text(if (e.item.kind == ScheduleItemKind.Movie) "Movie" else "Episode"),
                "runtimeMs" to number(e.item.runtimeMs),
                "createdAtMs" to number(e.item.createdAtMs),
                "deletedAtMs" to number(e.item.deletedAtMs),
                "premiereDate" to text(e.item.premiereDate),
                "productionYear" to number(e.item.productionYear),
                "seriesId" to text(e.item.seriesId?.value),
                "seasonNumber" to number(e.item.seasonNumber),
                "episodeNumber" to number(e.item.episodeNumber),
                "officialRating" to text(e.officialRating),
                "genres" to strings(e.genres),
                "tags" to strings(e.tags),
                "studios" to strings(e.studios),
                "name" to text(e.name),
            ),
        )

    private fun seriesJson(s: RuleSeries): JsonObject =
        JsonObject(
            mapOf(
                "id" to text(s.id.value),
                "officialRating" to text(s.officialRating),
                "genres" to strings(s.genres),
                "tags" to strings(s.tags),
                "studios" to strings(s.studios),
                "name" to text(s.name),
            ),
        )

    private fun collectionJson(c: RuleCollection): JsonObject =
        JsonObject(
            mapOf(
                "id" to text(c.id.value),
                "movies" to ids(c.movies),
                "episodes" to ids(c.episodes),
                "series" to ids(c.series),
                "seasons" to ids(c.seasons),
                "name" to text(c.name),
            ),
        )

    private fun snapshotJson(s: RuleSnapshot): JsonObject =
        JsonObject(
            mapOf(
                "format" to text("audit-snapshot"),
                "version" to number(1),
                "cutoffMs" to number(s.cutoffMs),
                "collectionsSynced" to JsonPrimitive(s.collectionsSynced),
                "libraries" to ids(s.libraries),
                "items" to JsonArray(s.items.map(::itemJson)),
                "series" to
                    JsonArray(
                        s.series.values
                            .sortedBy { it.id.value }
                            .map(::seriesJson),
                    ),
                "collections" to
                    JsonArray(
                        s.collections.values
                            .sortedBy { it.id.value }
                            .map(::collectionJson),
                    ),
            ),
        )

    @Test
    fun theAppsOwnReadOfTheFixtureRowsMakesTheSnapshotTheExporterMakes() {
        val (snapshot, dropped) = appSnapshot(json("rows.json"))
        assertEquals(json("expected-snapshot.json"), snapshotJson(snapshot))
        val expected = json("expected-dropped.json").mapValues { it.value.jsonPrimitive.int }
        assertEquals(expected, dropped)
    }

    @Test
    fun theSnapshotKeepsTheOddValuesTheAppKeeps() {
        val (snapshot, _) = appSnapshot(json("rows.json"))
        val byName = snapshot.items.associateBy { it.name }
        // "" and " " ratings stay as they are, a season that is not canonical is no season, and an episode of a
        // series the cache does not hold stays in, with no series row.
        assertEquals("", byName.getValue("Renol Bratu").officialRating)
        assertEquals(" ", byName.getValue("Fabra Mizu").officialRating)
        assertNull(byName.getValue("Second").seasonId)
        assertTrue("Fourth" in byName)
        assertEquals(1, snapshot.series.size)
    }
}
