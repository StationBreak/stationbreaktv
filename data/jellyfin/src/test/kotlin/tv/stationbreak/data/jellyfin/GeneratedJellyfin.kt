// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.testing.SyntheticLibrary
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A fake Jellyfin `GET /Items` that pages a [SyntheticLibrary] the way the real one does: `startIndex` and `limit`,
 * the total only when asked for, `minDateLastSaved` as a filter, and the optional fields only when `fields` names them.
 * Items are rendered one page at a time, so a 15,000-item library is never held as text.
 */
internal fun FakeJellyfin.serve(library: SyntheticLibrary) {
    on("GET", "/Items") { request ->
        val url = request.url
        val start = url.queryParameter("startIndex")?.toInt() ?: 0
        val limit = url.queryParameter("limit")?.toInt() ?: library.size
        val since = url.queryParameter("minDateLastSaved")?.let(Instant::parse)
        val fields =
            url
                .queryParameter("fields")
                .orEmpty()
                .split(',')
                .toSet()
        val matching =
            if (since == null) {
                library.page(start, limit)
            } else {
                (0 until library.size)
                    .filter { library.savedAt(it) >= since }
                    .drop(start)
                    .take(limit)
                    .map(library::item)
            }
        val total = if (url.queryParameter("enableTotalRecordCount") == "true") library.size else matching.size
        val body =
            buildJsonObject {
                put("Items", JsonArray(matching.map { wireItem(it, fields) }))
                put("TotalRecordCount", total)
                put("StartIndex", start)
            }
        json(body.toString())
    }
}

/** An item as the server writes it: always the ID, and the rest as the real one sends it for the fields asked for. */
@Suppress("CyclomaticComplexMethod") // One optional field per branch: the shape of the wire format.
internal fun wireItem(
    item: MediaItem,
    fields: Set<String>,
): JsonObject {
    if (fields.isEmpty() || fields == setOf("")) return buildJsonObject { put("Id", item.id.value) }
    return buildJsonObject {
        put("Id", item.id.value)
        put("Name", item.name)
        put("Type", wireType(item.kind))
        put("RunTimeTicks", item.runtime?.let(::ticks)?.let(::JsonPrimitive) ?: JsonNull)
        item.productionYear?.let { put("ProductionYear", it) }
        item.officialRating?.let { put("OfficialRating", it) }
        item.seriesId?.let { put("SeriesId", it.value) }
        item.seasonNumber?.let { put("ParentIndexNumber", it) }
        item.episodeNumber?.let { put("IndexNumber", it) }
        if ("ParentId" in fields) item.parentId?.let { put("ParentId", it.value) }
        if ("DateCreated" in fields) item.dateCreated?.let { put("DateCreated", it.toString()) }
        if ("Genres" in fields) put("Genres", JsonArray(item.genres.map(::JsonPrimitive)))
        if ("Tags" in fields) put("Tags", JsonArray(item.tags.map(::JsonPrimitive)))
    }
}

private fun ticks(runtime: Duration): Long = runtime.inWholeNanoseconds / 100

private fun wireType(kind: ItemKind): String =
    when (kind) {
        ItemKind.Movie -> "Movie"
        ItemKind.Episode -> "Episode"
        ItemKind.Video -> "Video"
        ItemKind.MusicVideo -> "MusicVideo"
        else -> kind.name
    }
