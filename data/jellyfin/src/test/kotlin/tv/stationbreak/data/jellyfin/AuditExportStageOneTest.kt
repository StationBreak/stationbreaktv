// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.SeriesInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Stage 1 of the lineup audit's exporter (tools/audit-export/FORMAT.md): a server's answer as the library cache holds
 * it. The fixture's answers (`server.json`) go through this module's own decoder and mappers, and what they make is
 * held
 * to the rows the exporter's fixture holds (`rows.json`), so the exporter's Python mapping is checked against the app's
 * code, not only against itself. Stage 2 is `AuditExportFixtureTest` in data:library.
 */
class AuditExportStageOneTest {
    private val fixtures = File(requireNotNull(System.getProperty("auditExport.fixtures")) { "No fixture folder" })

    private fun json(name: String): JsonObject = Json.parseToJsonElement(File(fixtures, name).readText()).jsonObject

    private fun <T> decoded(
        text: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T = (Wire.parse(text, serializer) as ServerResult.Success).value

    private fun page(items: JsonElement): String {
        val total = JsonPrimitive(0)
        return JsonObject(mapOf("Items" to items, "TotalRecordCount" to total)).toString()
    }

    private fun text(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    private fun number(value: Number?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    private fun strings(values: List<String>): JsonElement = JsonArray(values.map(::JsonPrimitive))

    private fun row(
        item: MediaItem,
        library: String,
    ): JsonObject =
        JsonObject(
            mapOf(
                "itemId" to text(item.id.value),
                "libraryId" to text(library),
                "kind" to text(item.kind.name),
                "seriesId" to text(item.seriesId?.value),
                "seasonId" to text(item.seasonId?.value),
                "seasonNumber" to number(item.seasonNumber),
                "episodeNumber" to number(item.episodeNumber),
                "runtimeMs" to number(item.runtime?.inWholeMilliseconds),
                "createdAtMs" to number(item.dateCreated?.toEpochMilliseconds()),
                "premiereDate" to text(item.premiereDate),
                "productionYear" to number(item.productionYear),
                "deletedAtMs" to JsonNull,
                "seenFromMs" to number(0),
                "officialRating" to text(item.officialRating),
                "genres" to strings(item.genres),
                "tags" to strings(item.tags),
                "studios" to strings(item.studios),
                "name" to text(item.name),
            ),
        )

    private fun seriesRow(s: SeriesInfo): JsonObject =
        JsonObject(
            mapOf(
                "seriesId" to text(s.id.value),
                "name" to text(s.name),
                "officialRating" to text(s.officialRating),
                "genres" to strings(s.genres),
                "tags" to strings(s.tags),
                "studios" to strings(s.studios),
            ),
        )

    // The kinds the cache keeps of a member's type: the four a collection's sets hold.
    private val storedKinds =
        mapOf(
            CollectionMemberKind.Movie to "movie",
            CollectionMemberKind.Episode to "episode",
            CollectionMemberKind.Series to "series",
            CollectionMemberKind.Season to "season",
        )

    private fun libraries(
        server: JsonObject,
        key: String,
    ): List<String> =
        server
            .getValue(key)
            .jsonObject.keys
            .sortedBy { it.replace("-", "").lowercase() }

    @Test
    fun theAppsOwnDecoderAndMappersMakeTheRowsTheExporterFixtureHolds() {
        val server = json("server.json")
        val rows = json("rows.json")
        val items =
            libraries(server, "items").flatMap { library ->
                val dtos = decoded(page(server.getValue("items").jsonObject.getValue(library)), ItemsDto.serializer())
                dtos.items.map { row(toMediaItem(it), library) }
            }
        assertEquals(rows.getValue("rows").jsonArray.toList(), items)
        val series =
            libraries(server, "series").flatMap { library ->
                val answer = page(server.getValue("series").jsonObject.getValue(library))
                val dtos = decoded(answer, SeriesItemsDto.serializer())
                dtos.items.map { seriesRow(toSeriesInfo(it)) }
            }
        assertEquals(rows.getValue("series").jsonArray.toList(), series)
    }

    @Test
    fun theCollectionsAndTheirMembersAreWhatTheCacheKeeps() {
        val server = json("server.json")
        val rows = json("rows.json")
        val boxsets = decoded(page(server.getValue("boxsets")), CollectionsDto.serializer())
        val made =
            boxsets.items.map { dto ->
                val info = toCollectionInfo(dto)
                val members = server.getValue("members").jsonObject.getValue(dto.id)
                val kept =
                    decoded(page(members), CollectionMembersDto.serializer()).items.mapNotNull {
                        val member = toCollectionMember(it)
                        storedKinds[member.kind]?.let { kind ->
                            JsonObject(mapOf("memberId" to text(member.id.value), "kind" to text(kind)))
                        }
                    }
                JsonObject(
                    mapOf(
                        "collectionId" to text(info.id.value),
                        "name" to text(info.name),
                        "members" to JsonArray(kept),
                    ),
                )
            }
        assertEquals(rows.getValue("collections").jsonArray.toList(), made)
    }
}
