// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UnsupportedKindTest {
    private fun unsupported(text: String): RulesResult.Unsupported {
        val result = RulesReader.read(text)
        assertIs<RulesResult.Unsupported>(result, "expected Unsupported, got $result")
        return result
    }

    private fun orderingOf(mode: String) = minimalRules().with("ordering", obj("mode" to JsonPrimitive(mode)))

    private fun fillerOf(mode: String): JsonObject {
        val filler = obj("mode" to JsonPrimitive(mode), "source" to libraryJson())
        return minimalRules().with("filler", filler)
    }

    private fun playlist() = source("playlist", "id" to JsonPrimitive(PLACEHOLDER_ID))

    @Test
    fun anUnknownSourceKindIsUnsupportedNotDropped() {
        val text = minimalRules().with("sources", array(libraryJson(), playlist())).text()
        val result = unsupported(text)
        assertNull(result.rules)
        assertEquals(true, result.reason.contains("playlist"))
    }

    @Test
    fun anUnknownKindInAnExclusionIsUnsupported() {
        unsupported(minimalRules().with("exclusions", array(source("mood", "name" to JsonPrimitive("Calm")))).text())
    }

    @Test
    fun anUnknownOrderingModeIsUnsupported() {
        unsupported(orderingOf("weighted").text())
        unsupported(orderingOf("SHUFFLE").text())
    }

    @Test
    fun anUnknownFillerModeIsUnsupported() {
        unsupported(fillerOf("pad-to-grid").text())
        unsupported(fillerOf("weekly").text())
    }

    @Test
    fun anUnknownKindInAFillerSourceIsUnsupported() {
        val filler = obj("mode" to JsonPrimitive("none"), "source" to playlist())
        unsupported(minimalRules().with("filler", filler).text())
    }

    @Test
    fun everyFillerModeButNoneIsUnsupportedButStillRead() {
        val names =
            listOf("filler-pad-to-grid.json", "filler-tag-source.json", "filler-between-every-n.json", "full.json")
        for (name in names) {
            val result = unsupported(ExampleFiles.valid.getValue(name))
            assertNotNull(result.rules, name)
            assertEquals(true, result.reason.contains("filler"), name)
        }
    }

    @Test
    fun fillerNoneIsSupportedAndAnEveryIsKeptAndIgnored() {
        val filler = obj("mode" to JsonPrimitive("none"), "every" to JsonPrimitive(5))
        val rules = readOk(minimalRules().with("filler", filler).text())
        assertEquals(5, rules.filler?.every)
        assertEquals(FillerMode.None, rules.fillerMode)
        assertEquals(true, RulesWriter.write(rules).contains("\"every\": 5"))
    }

    @Test
    fun aMalformedFileIsInvalidEvenWhenItAlsoHasAnUnknownKind() {
        val text =
            minimalRules()
                .with("sources", array(source("playlist", "id" to JsonPrimitive(PLACEHOLDER_ID))))
                .with("seed", "007")
                .text()
        assertIs<RulesResult.Invalid>(RulesReader.read(text))
    }

    @Test
    fun theInvalidExamplesAreRejectedAsTheDocumentSays() {
        val unsupportedKinds =
            setOf("unknown-source-kind.json", "ordering-mode-upper-case.json", "filler-mode-kebab-case.json")
        for ((name, text) in ExampleFiles.invalid) {
            val result = RulesReader.read(text)
            if (name in unsupportedKinds) {
                assertIs<RulesResult.Unsupported>(result, name)
            } else {
                assertIs<RulesResult.Invalid>(result, "$name: $result")
            }
        }
    }
}
