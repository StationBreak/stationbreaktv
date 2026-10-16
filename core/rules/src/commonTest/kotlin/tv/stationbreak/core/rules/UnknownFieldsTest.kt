// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class UnknownFieldsTest {
    private val text = ExampleFiles.valid.getValue("forward-compat-extras.json")
    private val rules = readOk(text)

    @Test
    fun keepsAnExtraFieldAtTheTopLevel() {
        val extra = rules.extras.getValue("futureTopLevel") as JsonObject
        assertEquals(JsonArray(listOf(1, 2, 3).map { JsonPrimitive(it) }), extra.getValue("anything"))
        assertEquals(setOf("futureTopLevel"), rules.extras.keys)
    }

    @Test
    fun keepsExtraFieldsInASourceAndAnExclusion() {
        assertEquals(JsonPrimitive(true), rules.sources[0].extras.getValue("futureSourceField"))
        assertEquals(
            JsonPrimitive("x"),
            rules.exclusions
                ?.get(0)
                ?.extras
                ?.getValue("futureExclusionField"),
        )
    }

    @Test
    fun keepsExtraFieldsInOrderingTimingAndTheBroadcastDay() {
        assertEquals(JsonPrimitive(1), rules.ordering?.extras?.getValue("futureOrderingField"))
        assertEquals(JsonNull, rules.timing.extras.getValue("futureTimingField"))
        assertEquals(
            JsonArray(emptyList()),
            rules.timing.broadcastDay.extras
                .getValue("futureDayField"),
        )
    }

    @Test
    fun keepsExtraFieldsInTheFillerAndItsSource() {
        val filler = assertNotNull(rules.filler)
        assertEquals(JsonPrimitive("y"), filler.extras.getValue("futureFillerField"))
        assertEquals(JsonPrimitive(2), filler.source?.extras?.getValue("futureFillerSourceField"))
    }

    @Test
    fun writesEveryExtraFieldBackWithTheSameValue() {
        val original = Json.parseToJsonElement(text)
        val written = Json.parseToJsonElement(RulesWriter.write(rules))
        assertEquals(original, written)
    }

    @Test
    fun anExtraFieldDoesNotChangeWhatTheKnownFieldsSay() {
        val plain = readOk(minimalRules().text())
        val withExtra = readOk(minimalRules().with("note", "hello").text())
        assertEquals(plain.sources, withExtra.sources)
        assertEquals(plain.seed, withExtra.seed)
        assertEquals(JsonPrimitive("hello"), withExtra.extras.getValue("note"))
    }

    @Test
    fun keepsExtraFieldsOfEveryJsonKindUnchanged() {
        val extras: Map<String, JsonElement> =
            mapOf(
                "a" to JsonNull,
                "b" to JsonPrimitive(false),
                "c" to JsonPrimitive(-12),
                "d" to JsonPrimitive("café 😀"),
                "e" to JsonObject(mapOf("nested" to JsonArray(listOf(JsonPrimitive(1.5))))),
            )
        val input = JsonObject(minimalRules() + extras)
        val written = Json.parseToJsonElement(RulesWriter.write(readOk(input.text()))) as JsonObject
        for ((key, value) in extras) assertEquals(value, written[key], key)
    }
}
