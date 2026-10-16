// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// docs/rules-format.md must cover every topic an outside developer needs (spec 07, task 1.0), and its full examples
// must be valid.
class RulesFormatDocTest {
    private val doc: String by lazy {
        assertTrue(RulesFormatFiles.docFile.isFile, "missing ${RulesFormatFiles.docFile}")
        RulesFormatFiles.docFile.readText()
    }

    private val headings: List<String> by lazy {
        doc.lines().filter { it.startsWith("## ") }.map { it.removePrefix("## ").lowercase() }
    }

    @Test
    fun hasASectionForEachRequiredTopic() {
        val missing = topics.filter { topic -> headings.none { topic in it } }
        assertTrue(missing.isEmpty(), "no section heading mentions: $missing")
    }

    @Test
    fun theTimingTableHasRowsForGraceAndGrid() {
        val rows = doc.lines().filter { it.startsWith("| `graceHours` |") || it.startsWith("| `gridMinutes` |") }
        assertEquals(2, rows.size, "one Timing table row each for graceHours and gridMinutes: $rows")
        val grace = rows.single { "graceHours" in it }
        val grid = rows.single { "gridMinutes" in it }
        assertTrue("`6`" in grace && "0 to 48" in grace, "graceHours: default 6, 0 to 48")
        assertTrue("`30`" in grid && "15, 30 or 60" in grid, "gridMinutes: default 30")
    }

    @Test
    fun theTimingTableHasARowForTheMinimumRuntime() {
        val rows = doc.lines().filter { it.startsWith("| `minRuntimeSeconds` |") }
        assertEquals(1, rows.size, "one Timing table row for minRuntimeSeconds: $rows")
        assertTrue("`60`" in rows.single() && "1 to 3600" in rows.single(), "minRuntimeSeconds: default 60, 1 to 3600")
    }

    @Test
    fun theGridIsDescribedByGridMinutesNotByHalfHours() {
        val align = doc.lines().single { it.startsWith("| `alignToGrid` |") }
        assertTrue(":00 and :30" !in align && "half-hour" !in align, "alignToGrid row names a fixed grid: $align")
        assertTrue("gridMinutes" in align, "alignToGrid row points at gridMinutes: $align")
        assertTrue("The \"grid\" for `alignToGrid` is the :00 and :30 marks" !in doc, "the grid sentence is stale")
        assertTrue("with 60 it must be :00" in doc, "start says how it relates to gridMinutes")
    }

    @Test
    fun statesTheRatingLadder() {
        for (row in ladderRows) assertTrue(row in doc, "ladder row missing: $row")
    }

    @Test
    fun theLadderTableInTheDocumentIsTheOneInCode() {
        val start = doc.lines().indexOfFirst { it.startsWith("| Rank | Ratings |") }
        assertTrue(start >= 0, "no ladder table")
        val rows = doc.lines().drop(start + 2).takeWhile { it.startsWith("| ") }
        val fromCode = RatingLadder.tiers.mapIndexed { rank, names -> "| $rank | ${names.joinToString(", ")} |" }
        assertEquals(fromCode, rows)
    }

    @Test
    fun theNormalisationRuleIsNoLongerMarkedProvisional() {
        assertTrue("Provisional" !in doc, "the rating rule was checked against Jellyfin (task 3.5)")
        assertTrue("TV-PG-" in doc && "TV-14-" in doc && "TV-MA-" in doc, "the descriptor rule names the three tiers")
    }

    @Test
    fun statesTheAutoSeedByteLayout() {
        for (needle in listOf("splitmix64(fnv1a64(", "0x00", "`library`", "`genre`", "`collection`")) {
            assertTrue(needle in doc, "auto-seed layout is missing: $needle")
        }
    }

    @Test
    fun statesThePrimitiveConstants() {
        val constants =
            listOf(
                "0x9E3779B97F4A7C15",
                "0xBF58476D1CE4E5B9",
                "0x94D049BB133111EB",
                "0xcbf29ce484222325",
                "0x100000001b3",
            )
        for (c in constants) {
            assertTrue(c in doc, "constant missing: $c")
        }
    }

    @Test
    fun hasTheScopeSectionsAndBanner() {
        assertTrue("Draft — v1 is final once F1.1 completes the scheduling section and the vectors land." in doc)
        for (h in listOf("items and the pool", "building the schedule", "verify your client", "glossary")) {
            assertTrue(headings.any { h in it }, "no section heading mentions: $h")
        }
        assertTrue("specified by F1.1" in doc)
    }

    @Test
    fun noNoteSaysTheVectorsWillBePublishedLater() {
        assertTrue("will be published with F1.1" !in doc, "a stale 'will be published with F1.1' note")
    }

    @Test
    fun theVectorsListNamesExactlyTheFilesInTheVectorsFolder() {
        val start = doc.lines().indexOfFirst { it.startsWith("- `docs/rules-format/vectors/`:") }
        assertTrue(start >= 0, "no vectors bullet under Files")
        val bullet =
            doc
                .lines()
                .drop(start)
                .takeWhile { it.isNotBlank() }
                .joinToString(" ")
        val listed = Regex("`([a-z0-9-]+\\.json)`").findAll(bullet).map { it.groupValues[1] }.toSet()
        val onDisk =
            RulesFormatFiles.dir
                .resolve("vectors")
                .listFiles { f -> f.isFile && f.name.endsWith(".json") }
                .orEmpty()
                .map { it.name }
                .toSet()
        assertEquals(onDisk, listed, "the Files list and docs/rules-format/vectors/ must agree")
        assertTrue("lineup.json" in listed, "the lineup vectors are part of the contract")
    }

    @Test
    fun statesTheReaderAndWriterRules() {
        for (
        needle in
        listOf(
            "A writer must write the lowest `formatVersion` whose features the file uses.",
            "code points",
            "standard (non-daylight-saving) offset",
            "RFC 2119",
            "RFC 8174",
            "unsupported",
            "unpaired surrogate",
        )
        ) {
            assertTrue(needle in doc, "missing statement: $needle")
        }
        assertTrue("RANDOM_IN_ORDER" !in doc && "SHUFFLE" !in doc, "ordering modes are lowerCamel")
        assertTrue("`between-every-N`" !in doc && "`pad-to-grid`" !in doc, "filler modes are lowerCamel")
        assertTrue("Bounded integers" !in doc, "bounded integers belong to F1.1")
    }

    @Test
    fun mentionsEveryExampleFile() {
        val files = RulesFormatFiles.valid.keys
        assertTrue(files.isNotEmpty(), "no files in examples/")
        val missing = files.filter { "examples/$it" !in doc }
        assertTrue(missing.isEmpty(), "examples not mentioned in the document: $missing")
    }

    @Test
    fun everyCompleteJsonExampleInTheDocumentValidates() {
        val schema =
            SchemaRegistry
                .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(RulesFormatFiles.schemaFile.readText())
        val blocks =
            Regex("```json\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
                .findAll(doc)
                .map { it.groupValues[1] }
                .filter { "\"formatVersion\"" in it }
                .toList()
        assertTrue(blocks.isNotEmpty(), "no complete JSON examples in the document")
        for (b in blocks) assertTrue(schema.validate(b, InputFormat.JSON).isEmpty(), "invalid example in document:\n$b")
    }

    private companion object {
        val topics =
            listOf(
                "version",
                "sources",
                "exclusions",
                "ordering",
                "timing",
                "filler",
                "seed",
                "rating",
                "item id",
                "string matching",
                "unknown",
            )
        val ladderRows =
            listOf(
                "| 0 | TV-Y, TV-G, G, Approved |",
                "| 1 | TV-Y7, TV-Y7-FV |",
                "| 2 | TV-PG, PG |",
                "| 3 | PG-13 |",
                "| 4 | TV-14 |",
                "| 5 | R |",
                "| 6 | TV-MA, NC-17 |",
                "| 7 | TV-X, TV-AO |",
            )
    }
}
