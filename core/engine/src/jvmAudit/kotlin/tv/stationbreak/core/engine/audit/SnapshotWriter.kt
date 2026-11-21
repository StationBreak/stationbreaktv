// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.RulesWriter

/**
 * Writes the `audit-snapshot` file: the same input always gives the same bytes. Keys are in a fixed order, items,
 * series and collections are in canonical-ID order, and a collection's members too. The top level is laid out one key
 * to a line and each item, series and collection on a line of its own, so a diff of two exports reads by record.
 */
internal object SnapshotWriter {
    fun write(input: AuditInput): String {
        val s = input.snapshot
        val top = LinkedHashMap<String, JsonElement>()
        top["format"] = JsonPrimitive(AuditSnapshotCodec.FORMAT)
        top["version"] = JsonPrimitive(AuditSnapshotCodec.VERSION)
        top["cutoffMs"] = JsonPrimitive(s.cutoffMs)
        top["collectionsSynced"] = JsonPrimitive(s.collectionsSynced)
        top["libraries"] =
            JsonArray(
                s.libraries
                    .map { it.value }
                    .sorted()
                    .map(::JsonPrimitive),
            )
        top["items"] = JsonArray(s.items.sortedBy { it.item.id.value }.map(::itemOf))
        top["series"] =
            JsonArray(
                s.series.values
                    .sortedBy { it.id.value }
                    .map(::seriesOf),
            )
        top["collections"] =
            JsonArray(
                s.collections.values
                    .sortedBy { it.id.value }
                    .map(::collectionOf),
            )
        input.viewer?.let { top["viewer"] = viewerOf(it) }
        return JsonLayout.layout(top)
    }

    private fun text(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    private fun number(value: Number?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    private fun id(value: CanonicalId?): JsonElement = text(value?.value)

    private fun texts(values: List<String>): JsonElement = JsonArray(values.map(::JsonPrimitive))

    private fun itemOf(entry: RuleItem): JsonObject {
        val item = entry.item
        return JsonObject(
            linkedMapOf(
                "id" to id(item.id),
                "libraryId" to id(entry.libraryId),
                "seasonId" to id(entry.seasonId),
                "kind" to JsonPrimitive(if (item.kind == ScheduleItemKind.Movie) "Movie" else "Episode"),
                "runtimeMs" to JsonPrimitive(item.runtimeMs),
                "createdAtMs" to JsonPrimitive(item.createdAtMs),
                "deletedAtMs" to number(item.deletedAtMs),
                "premiereDate" to text(item.premiereDate),
                "productionYear" to number(item.productionYear),
                "seriesId" to id(item.seriesId),
                "seasonNumber" to number(item.seasonNumber),
                "episodeNumber" to number(item.episodeNumber),
                "officialRating" to text(entry.officialRating),
                "genres" to texts(entry.genres),
                "tags" to texts(entry.tags),
                "studios" to texts(entry.studios),
                "name" to text(entry.name),
            ),
        )
    }

    // `providerIds` is written only when the series has some, so a file without them keeps its bytes.
    private fun seriesOf(series: RuleSeries): JsonObject {
        val fields =
            linkedMapOf<String, JsonElement>(
                "id" to id(series.id),
                "officialRating" to text(series.officialRating),
                "genres" to texts(series.genres),
                "tags" to texts(series.tags),
                "studios" to texts(series.studios),
                "name" to text(series.name),
            )
        if (series.providerIds.isNotEmpty()) {
            fields["providerIds"] = JsonObject(series.providerIds.toSortedMap().mapValues { JsonPrimitive(it.value) })
        }
        return JsonObject(fields)
    }

    private fun ids(values: Set<CanonicalId>): JsonElement {
        val sorted = values.map { it.value }.sorted()
        return JsonArray(sorted.map(::JsonPrimitive))
    }

    private fun collectionOf(collection: RuleCollection): JsonObject =
        JsonObject(
            linkedMapOf(
                "id" to id(collection.id),
                "movies" to ids(collection.movies),
                "episodes" to ids(collection.episodes),
                "series" to ids(collection.series),
                "seasons" to ids(collection.seasons),
                "name" to text(collection.name),
            ),
        )

    private fun viewerOf(viewer: Viewer): JsonObject =
        JsonObject(
            linkedMapOf(
                "switchedOn" to texts(viewer.switchedOn),
                "customChannels" to
                    JsonArray(
                        viewer.customChannels.map {
                            JsonObject(
                                linkedMapOf(
                                    "name" to JsonPrimitive(it.name),
                                    "rules" to Json.parseToJsonElement(RulesWriter.write(it.rules)),
                                ),
                            )
                        },
                    ),
            ),
        )
}
