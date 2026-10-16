// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler.vectors

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Helpers to read the vector files (`docs/rules-format/vectors/`), which write every 64-bit value four ways. */
internal object VectorJson {
    private const val HEX_DIGITS_64 = 16
    private const val HEX_DIGITS_32 = 8
    private const val HEX_RADIX = 16

    fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    fun JsonElement.objects(): List<JsonObject> = jsonArray.map { it.jsonObject }

    fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    fun JsonObject.stringOrNull(name: String): String? = (getValue(name) as? JsonPrimitive)?.contentOrNull

    fun JsonObject.array(name: String): JsonArray = getValue(name).jsonArray

    fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject

    /** True for an object that is a 64-bit value: it has `dec`, `hex`, `hi` and `lo`. */
    fun isU64(element: JsonElement): Boolean = element is JsonObject && setOf("dec", "hex", "hi", "lo") == element.keys

    /** Reads a 64-bit value and checks that its decimal, hex and 32-bit halves all say the same number. */
    fun u64(element: JsonElement): ULong {
        val o = element.jsonObject
        assertTrue(isU64(o), "not a 64-bit value: $o")
        val dec = o.string("dec")
        val value = requireNotNull(dec.toULongOrNull()) { "bad decimal $dec" }
        assertEquals(value.toString(), dec, "decimal is not canonical: $dec")
        val hex = o.string("hex")
        assertEquals(HEX_DIGITS_64, hex.length, "hex is 16 digits: $hex")
        assertEquals(hex.lowercase(), hex, "hex is lower case: $hex")
        assertEquals(value, hex.toULong(HEX_RADIX), "hex differs from decimal: $o")
        val hi = o.string("hi")
        val lo = o.string("lo")
        assertEquals(HEX_DIGITS_32, hi.length, "hi is 8 digits: $hi")
        assertEquals(HEX_DIGITS_32, lo.length, "lo is 8 digits: $lo")
        assertEquals(value shr 32, hi.toULong(HEX_RADIX), "high half differs: $o")
        assertEquals(value and 0xFFFFFFFFuL, lo.toULong(HEX_RADIX), "low half differs: $o")
        return value
    }

    /** Every 64-bit value object anywhere in the tree. */
    fun allU64(element: JsonElement): List<JsonObject> =
        when {
            isU64(element) -> listOf(element as JsonObject)
            element is JsonObject -> element.values.flatMap { allU64(it) }
            element is JsonArray -> element.flatMap { allU64(it) }
            else -> emptyList()
        }

    fun hexToBytes(hex: String): ByteArray {
        val pairs = hex.chunked(2)
        return ByteArray(pairs.size) { pairs[it].toInt(HEX_RADIX).toByte() }
    }
}
