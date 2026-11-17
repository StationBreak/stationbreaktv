// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One flat JSON object on one line. Values are strings, numbers, booleans or null; nothing else is accepted. */
internal fun jsonObject(vararg fields: Pair<String, Any?>): String = jsonObject(fields.asList())

/** The same, from a list, encoded by kotlinx.serialization. A repeated name keeps its last value. */
internal fun jsonObject(fields: List<Pair<String, Any?>>): String =
    Json.encodeToString(
        JsonObject.serializer(),
        JsonObject(fields.associate { (name, value) -> name to element(value) }),
    )

private fun element(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> error("unsupported JSON value")
    }
