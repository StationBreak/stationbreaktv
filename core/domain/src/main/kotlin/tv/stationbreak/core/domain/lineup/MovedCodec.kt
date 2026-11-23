// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import tv.stationbreak.core.engine.lineup.LineupEdits
import tv.stationbreak.core.engine.lineup.MovedNotice
import tv.stationbreak.core.model.ChannelId

/** The `moved` list of the lineup file (version 8, #1097): see [LineupCodec]. */
internal object MovedCodec {
    // The pending notices (#1097) as the file holds them: an ID, two numbers and the epoch day, never a name or title.
    fun encode(notices: List<MovedNotice>): JsonElement =
        JsonArray(
            notices.map {
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(it.id.value),
                        "from" to JsonPrimitive(it.from),
                        "to" to JsonPrimitive(it.to),
                        "day" to JsonPrimitive(it.day),
                    ),
                )
            },
        )

    // The notices, read leniently: they are advice to the viewer, so an entry that does not read (a missing or mistyped
    // key, a number out of range, a channel that is gone) is dropped, an extra key is ignored, and a list that is not a
    // list is none. Nothing here can make the lineup unreadable.
    fun decode(
        element: JsonElement?,
        ids: Set<ChannelId>,
    ): List<MovedNotice> = (element as? JsonArray).orEmpty().mapNotNull { noticeOf(it, ids) }

    private fun noticeOf(
        element: JsonElement,
        ids: Set<ChannelId>,
    ): MovedNotice? {
        val entry = element as? JsonObject ?: return null
        val numbers = LineupEdits.MIN_NUMBER.toLong()..LineupEdits.MAX_NUMBER.toLong()
        val id =
            entry
                .text("id")
                ?.takeIf { it.isNotBlank() }
                ?.let(::ChannelId)
                ?.takeIf { it in ids }
        val from = entry.number("from")?.takeIf { it in numbers }?.toInt()
        val to = entry.number("to")?.takeIf { it in numbers }?.toInt()
        val day = entry.number("day")
        return id?.let { i -> from?.let { f -> to?.let { t -> day?.let { d -> MovedNotice(i, f, t, d) } } } }
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    // A JSON whole number; a string, a fraction or a boolean is none.
    private fun JsonObject.number(key: String): Long? {
        val value = get(key) as? JsonPrimitive
        return value?.takeIf { !it.isString }?.longOrNull
    }
}
