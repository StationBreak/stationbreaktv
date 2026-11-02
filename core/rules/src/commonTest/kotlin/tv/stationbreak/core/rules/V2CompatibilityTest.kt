// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.model.OrderingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** A version 2 reader reads a version 1 file as a version 1 reader does (docs/rules-format.md, "Format version"). */
class V2CompatibilityTest {
    private fun mode(name: String) = obj("mode" to JsonPrimitive(name))

    @Test
    fun everyV1ExampleStillReadsAndWritesIdentically() {
        for ((name, text) in ExampleFiles.valid) {
            val rules = rulesOf(RulesReader.read(text))
            assertEquals(1, rules?.formatVersion, name)
            assertEquals(text, RulesWriter.write(rules!!), name)
        }
    }

    @Test
    fun aVersion1FileUsingRotationIsUnsupported() {
        for (name in listOf("rotation", "inOrder")) {
            val result = RulesReader.read(minimalRules().with("ordering", mode(name)).text())
            assertIs<RulesResult.Unsupported>(result, name)
            assertEquals("ordering mode '$name' is not known to this version", result.reason)
            assertNull(result.rules)
        }
    }

    @Test
    fun aVersion1FileUsingAnItemSourceIsUnsupported() {
        val item = source("item", "id" to JsonPrimitive(PLACEHOLDER_ID))
        val asSource = minimalRules().with("sources", array(libraryJson(), item))
        assertIs<RulesResult.Unsupported>(RulesReader.read(asSource.text()))
        assertIs<RulesResult.Unsupported>(RulesReader.read(minimalRules().with("exclusions", array(item)).text()))
    }

    @Test
    fun aVersion1FileWithAFiltersFieldKeepsItAsAnExtra() {
        val filters = obj("kind" to JsonPrimitive("season"), "genres" to JsonPrimitive("nope"))
        val text = minimalRules().with("filters", filters).text()
        val rules = readOk(text)
        assertEquals(filters, rules.extras["filters"])
        assertNull(rules.filters)
        assertEquals(RulesWriter.write(rules), RulesWriter.write(readOk(RulesWriter.write(rules))))
        assertEquals(true, RulesWriter.write(rules).contains("\"filters\""))
    }

    @Test
    fun aVersion1FileWithCapsInOrderingKeepsThemAsExtras() {
        val caps = obj("series" to JsonPrimitive(99))
        val ordering = obj("mode" to JsonPrimitive("shuffle"), "caps" to caps)
        val rules = readOk(minimalRules().with("ordering", ordering).text())
        assertEquals(caps, rules.ordering?.extras?.get("caps"))
        assertNull(rules.ordering?.caps)
        assertNull(rules.effectiveCaps)
        val short = minimalRules().with("ordering", ordering)
        readOk(short.edit("timing") { it.with("epochHours", JsonPrimitive(12)) }.text())
    }

    @Test
    fun aVersion1FileWithBlockLengthAndShuffleIsUnchanged() {
        val text =
            minimalRules()
                .with("ordering", mode("shuffle"))
                .edit("timing") { it.with("blockLength", JsonPrimitive(3)) }
                .text()
        val rules = readOk(text)
        assertEquals(OrderingMode.Shuffle, rules.orderingMode)
        assertEquals(3, rules.timing.effectiveBlockLength)
        assertEquals(1, rules.requiredVersion)
        assertEquals(1, rules.formatVersion)
    }

    @Test
    fun aV1CopyWithAFiltersExtraUpgradesCleanly() {
        val old = readOk(minimalRules().with("filters", obj("kind" to JsonPrimitive("season"))).text())
        val upgraded = old.withVersion(2).copy(filters = Filters(null, null, null, null, null, FilterKind.Movie))
        val reread = readOk(RulesWriter.write(upgraded))
        assertEquals(2, reread.formatVersion)
        assertEquals(FilterKind.Movie, reread.filters?.kind)
        assertNull(reread.extras["filters"])
    }
}
