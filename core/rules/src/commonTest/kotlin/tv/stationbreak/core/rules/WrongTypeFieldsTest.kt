// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The general invalid-value rule of docs/rules-format.md (JSON profile): a known field whose value has the wrong JSON
 * type, is `null`, or is outside its range makes the file invalid. A reader never clamps, rounds, or replaces a bad
 * value with the default. Every known field is tried with every wrong type.
 */
class WrongTypeFieldsTest {
    private val aNull: JsonElement = JsonNull
    private val aBool: JsonElement = JsonPrimitive(true)
    private val aNumber: JsonElement = JsonPrimitive(1)
    private val aFraction: JsonElement = JsonPrimitive(1.5)
    private val aString: JsonElement = JsonPrimitive("1")
    private val anArray: JsonElement = JsonArray(emptyList())
    private val anObject: JsonElement = JsonObject(emptyMap())

    private val badForInt = listOf(aNull, aBool, aString, aFraction, anArray, anObject)
    private val badForString = listOf(aNull, aBool, aNumber, anArray, anObject)
    private val badForBoolean = listOf(aNull, aString, JsonPrimitive("true"), aNumber, JsonPrimitive(0), anArray, anObject)
    private val badForObject = listOf(aNull, aBool, aNumber, aString, anArray)
    private val badForArray = listOf(aNull, aBool, aNumber, aString, anObject)

    private fun timing(vararg fields: Pair<String, JsonElement>) =
        minimalRules().with("timing", obj("broadcastDay" to obj("utcOffsetMinutes" to JsonPrimitive(0)), *fields))

    private fun withSource(vararg fields: Pair<String, JsonElement>) =
        minimalRules().with("sources", JsonArray(listOf(JsonObject(mapOf(*fields)))))

    private fun filler(vararg fields: Pair<String, JsonElement>) = minimalRules().with("filler", JsonObject(mapOf(*fields)))

    private fun fillerSource() = source("library", "id" to JsonPrimitive(PLACEHOLDER_ID))

    private fun rejectsAll(
        field: String,
        values: List<JsonElement>,
        build: (JsonElement) -> JsonObject,
    ) {
        for (value in values) {
            val text = build(value).text()
            val result = RulesReader.read(text)
            assertTrue(result is RulesResult.Invalid, "$field = $value should be invalid, got $result")
        }
    }

    @Test
    fun topLevelFields() {
        rejectsAll("formatVersion", badForInt) { minimalRules().with("formatVersion", it) }
        rejectsAll("name", badForString) { minimalRules().with("name", it) }
        rejectsAll("seed", badForString) { minimalRules().with("seed", it) }
        rejectsAll("sources", badForArray) { minimalRules().with("sources", it) }
        rejectsAll("exclusions", badForArray) { minimalRules().with("exclusions", it) }
        rejectsAll("maxRating", badForString) { minimalRules().with("maxRating", it) }
        rejectsAll("ordering", badForObject) { minimalRules().with("ordering", it) }
        rejectsAll("ordering.mode", badForString) { minimalRules().with("ordering", obj("mode" to it)) }
        rejectsAll("timing", badForObject) { minimalRules().with("timing", it) }
        rejectsAll("filler", badForObject) { minimalRules().with("filler", it) }
    }

    @Test
    fun timingFields() {
        for (field in listOf("blockLength", "epochHours", "graceHours", "gridMinutes", "minRuntimeSeconds")) {
            rejectsAll("timing.$field", badForInt) { timing(field to it) }
        }
        rejectsAll("timing.alignToGrid", badForBoolean) { timing("alignToGrid" to it) }
        rejectsAll("timing.broadcastDay", badForObject) { minimalRules().with("timing", obj("broadcastDay" to it)) }
        rejectsAll("utcOffsetMinutes", badForInt) {
            minimalRules().with("timing", obj("broadcastDay" to obj("utcOffsetMinutes" to it)))
        }
        rejectsAll("start", badForString) {
            minimalRules().with(
                "timing",
                obj("broadcastDay" to obj("utcOffsetMinutes" to JsonPrimitive(0), "start" to it)),
            )
        }
    }

    @Test
    fun sourceFields() {
        rejectsAll("source", badForObject) { minimalRules().with("sources", JsonArray(listOf(it))) }
        rejectsAll("source.type", badForString) { withSource("type" to it, "id" to JsonPrimitive(PLACEHOLDER_ID)) }
        rejectsAll("source.id", badForString) { withSource("type" to JsonPrimitive("library"), "id" to it) }
        rejectsAll("source.name", badForString) { withSource("type" to JsonPrimitive("genre"), "name" to it) }
        rejectsAll("source.from", badForInt) {
            withSource("type" to JsonPrimitive("yearRange"), "from" to it, "to" to JsonPrimitive(2000))
        }
        rejectsAll("source.to", badForInt) {
            withSource("type" to JsonPrimitive("yearRange"), "from" to JsonPrimitive(1990), "to" to it)
        }
        rejectsAll("exclusion", badForObject) { minimalRules().with("exclusions", JsonArray(listOf(it))) }
        rejectsAll("exclusion.name", badForString) {
            minimalRules().with("exclusions", JsonArray(listOf(source("tag", "name" to it))))
        }
    }

    @Test
    fun fillerFields() {
        rejectsAll("filler.mode", badForString) { filler("mode" to it) }
        rejectsAll("filler.every", badForInt) {
            filler("mode" to JsonPrimitive("betweenEveryN"), "every" to it, "source" to fillerSource())
        }
        rejectsAll("filler.source", badForObject) {
            filler("mode" to JsonPrimitive("padToGrid"), "source" to it)
        }
        rejectsAll("filler.source.type", badForString) {
            filler("mode" to JsonPrimitive("padToGrid"), "source" to obj("type" to it, "id" to JsonPrimitive(PLACEHOLDER_ID)))
        }
    }

    @Test
    fun outOfRangeValuesAreNeverClamped() {
        val ranged =
            mapOf(
                "blockLength" to listOf(0, 101, -1),
                "epochHours" to listOf(0, 5, 25),
                "graceHours" to listOf(-1, 49),
                "gridMinutes" to listOf(0, 10, 45, 90),
                "minRuntimeSeconds" to listOf(0, 3601),
            )
        for ((field, values) in ranged) {
            rejectsAll("timing.$field", values.map { JsonPrimitive(it) }) { timing(field to it) }
        }
        assertIs<RulesResult.Invalid>(RulesReader.read(minimalRules().with("maxRating", "PG-99").text()))
        assertIs<RulesResult.Invalid>(RulesReader.read(minimalRules().with("name", "").text()))
    }

    @Test
    fun theInvalidExamplesForTheGeneralRuleAreInvalid() {
        for (name in listOf("block-length-0.json", "block-length-101.json", "max-rating-null.json", "align-to-grid-string.json")) {
            val result = RulesReader.read(ExampleFiles.invalid.getValue(name))
            assertTrue(result is RulesResult.Invalid, "$name should be invalid, got $result")
        }
    }
}
