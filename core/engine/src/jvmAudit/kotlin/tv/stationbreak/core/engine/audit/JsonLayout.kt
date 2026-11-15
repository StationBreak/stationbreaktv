// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The layout of the audit's JSON files: one key of the top level to a line, a list of objects one object to a line,
 * everything else compact on its key's line. The same input always gives the same bytes, and a diff of two files reads
 * by record.
 */
internal object JsonLayout {
    fun layout(top: Map<String, JsonElement>): String =
        top.entries.joinToString(",\n", prefix = "{\n", postfix = "\n}\n") { (key, value) ->
            val body = if (value is JsonArray && value.all { it is JsonObject }) records(value) else value.toString()
            "  \"$key\": $body"
        }

    private fun records(list: JsonArray): String =
        if (list.isEmpty()) "[]" else list.joinToString(",\n", prefix = "[\n", postfix = "\n  ]") { "    $it" }
}
