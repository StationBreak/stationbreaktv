// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Writes rules in one stable form: fixed field order, two-space indent, a closing newline. A field that has a default
 * is written only if the rules hold it, and fields in an `extras` come after the known ones, so a file read and written
 * again keeps its text. The seed is written as the canonical decimal string it was read from.
 */
object RulesWriter {
    @OptIn(ExperimentalSerializationApi::class)
    private val json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    fun write(rules: ChannelRules): String = json.encodeToString(JsonElement.serializer(), tree(rules)) + "\n"

    private fun tree(rules: ChannelRules): JsonObject =
        obj(rules.extras) {
            put("formatVersion", JsonPrimitive(rules.formatVersion))
            rules.name?.let { put("name", JsonPrimitive(it)) }
            put("seed", JsonPrimitive(rules.seed.format()))
            put("sources", JsonArray(rules.sources.map(::source)))
            rules.exclusions?.let { put("exclusions", JsonArray(it.map(::source))) }
            rules.maxRating?.let { put("maxRating", JsonPrimitive(it)) }
            rules.ordering?.let { ordering ->
                put("ordering", obj(ordering.extras) { put("mode", JsonPrimitive(ordering.mode.wireName)) })
            }
            put("timing", timing(rules.timing))
            rules.filler?.let { put("filler", filler(it)) }
        }

    private fun timing(timing: Timing): JsonObject =
        obj(timing.extras) {
            timing.blockLength?.let { put("blockLength", JsonPrimitive(it)) }
            timing.alignToGrid?.let { put("alignToGrid", JsonPrimitive(it)) }
            timing.epochHours?.let { put("epochHours", JsonPrimitive(it)) }
            put(
                "broadcastDay",
                obj(timing.broadcastDay.extras) {
                    put("utcOffsetMinutes", JsonPrimitive(timing.broadcastDay.utcOffsetMinutes))
                    timing.broadcastDay.start?.let { put("start", JsonPrimitive(it)) }
                },
            )
        }

    private fun filler(filler: Filler): JsonObject =
        obj(filler.extras) {
            put("mode", JsonPrimitive(filler.mode.wireName))
            filler.every?.let { put("every", JsonPrimitive(it)) }
            filler.source?.let { put("source", source(it)) }
        }

    private fun source(source: Source): JsonObject =
        obj(source.extras) {
            when (source) {
                is Source.Library -> {
                    typed("library", "id" to JsonPrimitive(source.id.value))
                }

                is Source.Genre -> {
                    typed("genre", "name" to JsonPrimitive(source.name))
                }

                is Source.Collection -> {
                    typed("collection", "id" to JsonPrimitive(source.id.value))
                }

                is Source.Tag -> {
                    typed("tag", "name" to JsonPrimitive(source.name))
                }

                is Source.YearRange -> {
                    typed("yearRange", "from" to JsonPrimitive(source.from), "to" to JsonPrimitive(source.to))
                }

                is Source.Series -> {
                    typed("series", "id" to JsonPrimitive(source.id.value))
                }
            }
        }

    private fun MutableMap<String, JsonElement>.typed(
        type: String,
        vararg fields: Pair<String, JsonElement>,
    ) {
        put("type", JsonPrimitive(type))
        putAll(fields)
    }

    /** Known fields first, in the order [known] puts them, then each extra that does not reuse a known key. */
    private fun obj(
        extras: JsonObject,
        known: MutableMap<String, JsonElement>.() -> Unit,
    ): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        fields.known()
        for ((key, value) in extras) if (key !in fields) fields[key] = value
        return JsonObject(fields)
    }
}
