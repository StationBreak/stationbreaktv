// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// The published schema and the published examples must agree (spec 07, task 1.0): every example validates, every file
// in examples/invalid/ is rejected. Each invalid file is its own test case, named after the file.
class SchemaExamplesTest {
    private val schema: Schema by lazy {
        assertTrue(RulesFormatFiles.schemaFile.isFile, "missing ${RulesFormatFiles.schemaFile}")
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(RulesFormatFiles.schemaFile.readText())
    }

    private val schemaV2: Schema by lazy {
        assertTrue(RulesFormatFiles.schemaV2File.isFile, "missing ${RulesFormatFiles.schemaV2File}")
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(RulesFormatFiles.schemaV2File.readText())
    }

    private fun errors(text: String) = schema.validate(text, InputFormat.JSON)

    @Test
    fun everyV2ExampleValidatesAgainstTheV2Schema() {
        assertTrue(RulesFormatFiles.validV2.isNotEmpty(), "no files in examples/v2/")
        val failures =
            RulesFormatFiles.validV2.mapNotNull { (name, text) ->
                schemaV2.validate(text, InputFormat.JSON).takeIf { it.isNotEmpty() }?.let { name to it }
            }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n") { (n, e) -> "$n: $e" })
    }

    @Test
    fun everyV1ExampleAlsoValidatesAgainstTheV2Schema() {
        val failures =
            RulesFormatFiles.valid.mapNotNull { (name, text) ->
                schemaV2.validate(text, InputFormat.JSON).takeIf { it.isNotEmpty() }?.let { name to it }
            }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n") { (n, e) -> "$n: $e" })
    }

    @Test
    fun everyV2InvalidExampleFailsTheV2SchemaOrTheReader() {
        val examples = RulesFormatFiles.validV2.keys
        assertTrue(examples.isNotEmpty())
        assertEquals(v2InvalidFiles.toSortedSet(), RulesFormatFiles.invalidV2.keys.toSortedSet())
        for ((name, text) in RulesFormatFiles.invalidV2) {
            // The schema checks shapes; the cross-field rules are the reader's (docs/rules-format.md, "Files").
            if (name !in readerOnly) {
                assertTrue(schemaV2.validate(text, InputFormat.JSON).isNotEmpty(), "$name should fail the v2 schema")
            }
            val result = RulesReader.read(text)
            if (name in unsupportedFiles) {
                assertTrue(result is RulesResult.Unsupported, "$name: $result")
            } else {
                assertTrue(result is RulesResult.Invalid, "$name: $result")
            }
        }
    }

    @Test
    fun theV2SchemaStaysOpen() {
        // Behavioural: a valid v2 file keeps validating with an unknown field at every level that has its own object.
        val full = Json.parseToJsonElement(RulesFormatFiles.validV2.getValue("full.json")).jsonObject
        val extra = "futureField" to JsonPrimitive(true)

        fun JsonObject.plus(
            key: String,
            change: (JsonObject) -> JsonObject,
        ) = JsonObject(this + (key to change(getValue(key).jsonObject)))

        fun JsonObject.add() = JsonObject(this + extra)
        val opened =
            listOf(
                full.add(),
                full.plus("ordering") { o -> o.plus("caps") { it.add() } },
                full.plus("filters") { it.add() },
                full.plus("filters") { f -> f.plus("years") { it.add() } },
                full.plus("filters") { f -> f.plus("runtime") { it.add() } },
            )
        for ((i, doc) in opened.withIndex()) {
            assertTrue(schemaV2.validate(doc.toString(), InputFormat.JSON).isEmpty(), "case $i should still validate")
        }
        assertTrue("https://json-schema.org/draft/2020-12/schema" in RulesFormatFiles.schemaV2File.readText())
    }

    @Test
    fun everyValidExampleValidates() {
        val examples = RulesFormatFiles.valid
        assertTrue(examples.isNotEmpty(), "no files in examples/")
        val failures =
            examples.mapNotNull { (name, text) ->
                errors(text).takeIf { it.isNotEmpty() }?.let { name to it }
            }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n") { (n, e) -> "$n: $e" })
    }

    @Test
    fun schemaDeclaresTheRightDialectAndStaysOpen() {
        assertTrue(RulesFormatFiles.schemaFile.isFile, "missing ${RulesFormatFiles.schemaFile}")
        val text = RulesFormatFiles.schemaFile.readText()
        assertTrue("https://json-schema.org/draft/2020-12/schema" in text)
        assertTrue("additionalProperties" !in text, "objects must stay open to extra fields")
        assertTrue("unevaluatedProperties" !in text, "objects must stay open to extra fields")
    }

    @Test
    fun everyInvalidFileHasATestCase() {
        assertEquals(invalidCases.toSortedSet(), RulesFormatFiles.invalid.keys.toSortedSet())
    }

    private fun rejects(name: String) {
        val text = RulesFormatFiles.invalid[name] ?: fail("missing examples/invalid/$name")
        assertTrue(errors(text).isNotEmpty(), "$name should be rejected by the schema")
    }

    @Test fun wrongType() = rejects("wrong-type.json")

    @Test fun missingFormatVersion() = rejects("missing-format-version.json")

    @Test fun formatVersionZero() = rejects("format-version-zero.json")

    @Test fun missingSeed() = rejects("missing-seed.json")

    @Test fun seedWithLeadingZeros() = rejects("seed-leading-zeros.json")

    @Test fun seedNegative() = rejects("seed-negative.json")

    @Test fun seedIsANumber() = rejects("seed-not-a-string.json")

    @Test fun hyphenatedId() = rejects("hyphenated-id.json")

    @Test fun upperCaseId() = rejects("uppercase-id.json")

    @Test fun ceilingNotOnTheLadder() = rejects("ceiling-off-ladder.json")

    @Test fun unknownSourceKind() = rejects("unknown-source-kind.json")

    @Test fun noSources() = rejects("no-sources.json")

    @Test fun betweenEveryNWithoutN() = rejects("filler-between-every-n-missing-n.json")

    @Test fun missingTiming() = rejects("missing-timing.json")

    @Test fun missingUtcOffset() = rejects("missing-utc-offset.json")

    @Test fun offsetNotAMultipleOf15() = rejects("offset-not-multiple-of-15.json")

    @Test fun offsetOutOfRange() = rejects("offset-out-of-range.json")

    @Test fun epochHoursNotOnTheList() = rejects("epoch-hours-5.json")

    @Test fun startNotOnTheHalfHour() = rejects("start-not-on-grid.json")

    @Test fun graceHoursAboveFortyEight() = rejects("grace-hours-49.json")

    @Test fun minRuntimeSecondsZero() = rejects("min-runtime-seconds-0.json")

    @Test fun gridMinutesOffTheList() = rejects("grid-minutes-45.json")

    @Test fun hourlyGridWithAHalfHourStart() = rejects("grid-60-start-half-hour.json")

    @Test fun blockLengthZero() = rejects("block-length-0.json")

    @Test fun blockLengthOver100() = rejects("block-length-101.json")

    @Test fun nullMaxRating() = rejects("max-rating-null.json")

    @Test fun alignToGridAsAString() = rejects("align-to-grid-string.json")

    @Test fun upperCaseOrderingMode() = rejects("ordering-mode-upper-case.json")

    @Test fun upperCaseFillerMode() = rejects("filler-mode-kebab-case.json")

    @Test fun nameOf101CodePoints() = rejects("name-101-code-points.json")

    @Test
    fun nameOf100AstralCodePointsIsValid() {
        val text = RulesFormatFiles.valid.getValue("name-100-code-points.json")
        assertTrue(errors(text).isEmpty())
    }

    private companion object {
        val unsupportedFiles =
            setOf(
                "v1-file-rotation.json",
                "v1-file-in-order.json",
                "v1-file-item-source.json",
                "filters-kind-season.json",
            )
        val readerOnly =
            setOf("caps-short-epoch.json", "filters-years-reversed.json", "filters-runtime-min-over-max.json")
        val v2InvalidFiles =
            listOf(
                "caps-zero.json",
                "caps-eleven.json",
                "caps-short-epoch.json",
                "filters-years-reversed.json",
                "filters-runtime-min-over-max.json",
                "filters-empty-genres.json",
                "item-source-upper-case-id.json",
                "v1-file-rotation.json",
                "v1-file-in-order.json",
                "v1-file-item-source.json",
                "filters-kind-season.json",
            )
        val invalidCases =
            listOf(
                "wrong-type.json",
                "missing-format-version.json",
                "format-version-zero.json",
                "missing-seed.json",
                "seed-leading-zeros.json",
                "seed-negative.json",
                "seed-not-a-string.json",
                "hyphenated-id.json",
                "uppercase-id.json",
                "ceiling-off-ladder.json",
                "unknown-source-kind.json",
                "no-sources.json",
                "filler-between-every-n-missing-n.json",
                "missing-timing.json",
                "missing-utc-offset.json",
                "offset-not-multiple-of-15.json",
                "offset-out-of-range.json",
                "epoch-hours-5.json",
                "start-not-on-grid.json",
                "grace-hours-49.json",
                "min-runtime-seconds-0.json",
                "grid-minutes-45.json",
                "grid-60-start-half-hour.json",
                "ordering-mode-upper-case.json",
                "filler-mode-kebab-case.json",
                "name-101-code-points.json",
                "block-length-0.json",
                "block-length-101.json",
                "max-rating-null.json",
                "align-to-grid-string.json",
            )
    }
}
