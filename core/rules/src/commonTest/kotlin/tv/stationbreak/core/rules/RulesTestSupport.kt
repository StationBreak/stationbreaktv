// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.assertIs

internal const val PLACEHOLDER_ID = "11111111111111111111111111111111"

/** The smallest valid rules file, as a JSON tree that tests change one thing in. */
internal fun minimalRules(): JsonObject =
    buildJsonObject {
        put("formatVersion", 1)
        put("seed", "1")
        putJsonArray("sources") {
            add(
                buildJsonObject {
                    put("type", "library")
                    put("id", PLACEHOLDER_ID)
                },
            )
        }
        putJsonObject("timing") { putJsonObject("broadcastDay") { put("utcOffsetMinutes", 0) } }
    }

internal fun JsonObject.with(
    key: String,
    value: JsonElement,
): JsonObject = JsonObject(this + (key to value))

internal fun JsonObject.with(
    key: String,
    value: String,
): JsonObject = with(key, JsonPrimitive(value))

internal fun JsonObject.without(key: String): JsonObject = JsonObject(this - key)

/** Changes the object at [key] (a nested object) with [change]. */
internal fun JsonObject.edit(
    key: String,
    change: (JsonObject) -> JsonObject,
): JsonObject = with(key, change(getValue(key) as JsonObject))

internal fun JsonObject.text(): String = Json.encodeToString(JsonElement.serializer(), this)

internal fun source(
    type: String,
    vararg fields: Pair<String, JsonElement>,
): JsonObject = JsonObject(mapOf("type" to JsonPrimitive(type)) + fields)

internal fun libraryJson(id: String = PLACEHOLDER_ID): JsonObject = source("library", "id" to JsonPrimitive(id))

internal fun array(vararg items: JsonElement): JsonArray = JsonArray(items.toList())

internal val nullJson: JsonElement = JsonNull

/** Reads [text], which must be Ok, and returns the rules. */
internal fun readOk(text: String): ChannelRules {
    val result = RulesReader.read(text)
    assertIs<RulesResult.Ok>(result, "expected Ok, got $result")
    return result.rules
}

/** The rules of a result that decoded fully: Ok, or Unsupported because of a filler mode this version does not schedule. */
internal fun rulesOf(result: RulesResult): ChannelRules? =
    when (result) {
        is RulesResult.Ok -> result.rules
        is RulesResult.Unsupported -> result.rules
        else -> null
    }
