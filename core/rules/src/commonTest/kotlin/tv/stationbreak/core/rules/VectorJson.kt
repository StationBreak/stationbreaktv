// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals

/** Helpers to read the vector files (`docs/rules-format/vectors/`). */
internal object VectorJson {
    private const val HEX_RADIX = 16

    fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    fun JsonElement.objects(): List<JsonObject> = jsonArray.map { it.jsonObject }

    fun JsonObject.array(name: String): JsonArray = getValue(name).jsonArray

    fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    /** The string at [name], or null when it is JSON null. */
    fun JsonObject.stringOrNull(name: String): String? = getValue(name).let { if (it is JsonNull) null else (it as JsonPrimitive).content }

    fun JsonObject.intOrNull(name: String): Int? = getValue(name).let { if (it is JsonNull) null else it.jsonPrimitive.content.toInt() }

    fun JsonObject.boolean(name: String): Boolean = getValue(name).jsonPrimitive.content.toBooleanStrict()

    /** Reads a 64-bit value object and checks that its decimal, hex and 32-bit halves all say the same number. */
    fun u64(element: JsonElement): ULong {
        val o = element.jsonObject
        val value = requireNotNull(o.string("dec").toULongOrNull()) { "bad decimal in $o" }
        assertEquals(value, o.string("hex").toULong(HEX_RADIX), "hex differs from decimal: $o")
        assertEquals(value shr 32, o.string("hi").toULong(HEX_RADIX), "high half differs: $o")
        assertEquals(value and 0xFFFFFFFFuL, o.string("lo").toULong(HEX_RADIX), "low half differs: $o")
        return value
    }

    fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(HEX_RADIX).toByte() }
}
