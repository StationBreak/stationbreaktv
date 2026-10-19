// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Helpers to read the vector files (`docs/rules-format/vectors/`). */
internal object VectorJson {
    fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    fun JsonElement.objects(): List<JsonObject> = jsonArray.map { it.jsonObject }

    fun JsonObject.array(name: String): JsonArray = getValue(name).jsonArray

    fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    fun JsonObject.strings(name: String): List<String> = array(name).map { it.jsonPrimitive.content }

    /** The string at [name], or null when it is JSON null or absent. */
    fun JsonObject.stringOrNull(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    fun JsonObject.intOrNull(name: String): Int? = stringOrNull(name)?.toInt()
}
