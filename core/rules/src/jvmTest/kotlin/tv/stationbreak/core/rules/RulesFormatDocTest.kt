// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlin.test.Test
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
    fun statesTheRatingLadder() {
        for (row in ladderRows) assertTrue(row in doc, "ladder row missing: $row")
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
    fun statesTheReaderAndWriterRules() {
        for (
        needle in
        listOf(
            "A writer must write the lowest `formatVersion` whose features the file uses.",
            "code points",
            "standard (non-daylight-saving) offset",
            "Provisional until checked against Jellyfin",
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
