// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
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

    private fun errors(text: String) = schema.validate(text, InputFormat.JSON)

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

    @Test fun upperCaseOrderingMode() = rejects("ordering-mode-upper-case.json")

    @Test fun upperCaseFillerMode() = rejects("filler-mode-kebab-case.json")

    @Test fun nameOf101CodePoints() = rejects("name-101-code-points.json")

    @Test
    fun nameOf100AstralCodePointsIsValid() {
        val text = RulesFormatFiles.valid.getValue("name-100-code-points.json")
        assertTrue(errors(text).isEmpty())
    }

    private companion object {
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
            )
    }
}
