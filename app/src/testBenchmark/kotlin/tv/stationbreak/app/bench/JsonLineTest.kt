// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonLineTest {
    @Test
    fun aFlatObjectKeepsItsFieldOrderOnOneLine() {
        assertEquals(
            "{\"run_id\":\"r1\",\"n\":$ORDINAL,\"ok\":true,\"gap\":null}",
            jsonObject("run_id" to "r1", "n" to ORDINAL, "ok" to true, "gap" to null),
        )
    }

    @Test
    fun quotesBackslashesAndControlCharactersReadBackUnchanged() {
        val hostile = "a\"b\\c\nd\u0001e"
        val line = jsonObject("text" to hostile, "n" to ORDINAL, "ok" to false, "gap" to null)
        assertFalse(line.contains('\n'))
        val read = Json.parseToJsonElement(line).jsonObject
        assertEquals(hostile, read.getValue("text").jsonPrimitive.content)
        assertEquals(ORDINAL, read.getValue("n").jsonPrimitive.int)
        assertEquals(false, read.getValue("ok").jsonPrimitive.boolean)
        assertEquals(JsonNull, read.getValue("gap"))
    }

    @Test
    fun anUnsupportedValueIsRefused() {
        assertThrows(IllegalStateException::class.java) { jsonObject("list" to listOf(1)) }
    }
}

private const val ORDINAL = 3
