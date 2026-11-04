// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import tv.stationbreak.core.model.OrderingMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** A version 3 reader reads version 1 and 2 files as before (docs/rules-format.md, "Format version"). */
class V3CompatibilityTest {
    @Test
    fun everyV1AndV2ExampleReadsAndWritesUnchanged() {
        for ((name, text) in ExampleFiles.valid) {
            val rules = rulesOf(RulesReader.read(text))
            assertEquals(1, rules?.formatVersion, name)
            assertEquals(text, RulesWriter.write(rules!!), name)
        }
        for ((name, text) in ExampleFiles.validV2) {
            val rules = readOk(text)
            assertEquals(2, rules.formatVersion, name)
            assertEquals(text, RulesWriter.write(rules), name)
            assertEquals(2, rules.requiredVersion, name)
            assertEquals(2, rules.normalised().formatVersion, name)
        }
    }

    @Test
    fun everyV3ExampleNeedsVersionThree() {
        for ((name, text) in ExampleFiles.validV3) {
            val rules = readOk(text)
            assertEquals(3, rules.formatVersion, name)
            assertEquals(3, rules.requiredVersion, name)
            assertEquals(3, rules.normalised().formatVersion, name)
            assertEquals(text, RulesWriter.write(rules), name)
        }
    }

    @Test
    fun normalisedPicksThreeOnlyForAV3Feature() {
        val plain = readOk(minimalRules().with("formatVersion", JsonPrimitive(3)).text())
        assertEquals(1, plain.requiredVersion)
        assertEquals(1, plain.normalised().formatVersion)

        val v2 = plain.copy(ordering = Ordering(OrderingMode.InOrder))
        assertEquals(2, v2.requiredVersion)
        assertEquals(2, v2.normalised().formatVersion)

        val features =
            listOf(
                plain.copy(sources = listOf(Source.Studio("Studio A"))),
                plain.copy(exclusions = listOf(Source.Studio("Studio A"))),
                plain.copy(filters = emptyFilters().copy(tags = listOf("Tag A"))),
                plain.copy(filters = emptyFilters().copy(studios = listOf("Studio A"))),
                plain.copy(filters = emptyFilters().copy(notGenres = listOf("Horror"))),
                plain.copy(filters = emptyFilters().copy(episodeMaxRuntimeMinutes = 30)),
                plain.copy(filters = emptyFilters().copy(movieMinRuntimeMinutes = 80)),
                plain.copy(ordering = Ordering(OrderingMode.Curated)),
                plain.copy(ordering = Ordering(OrderingMode.Shuffle, repeatGapMinutes = 60)),
                plain.copy(ordering = Ordering(OrderingMode.Shuffle, pairUpToMinutes = 20)),
            )
        for ((i, rules) in features.withIndex()) {
            assertEquals(3, rules.requiredVersion, "feature $i")
            assertEquals(3, rules.normalised().formatVersion, "feature $i")
        }
    }

    @Test
    fun moveToVersionThreeTurnsKnownExtrasIntoNothingSoTheyCanBeSetAsRealFields() {
        val filters =
            buildJsonObject {
                putJsonArray("tags") { add(JsonPrimitive("Tag A")) }
            }
        val v2 = readOk(minimalRules().with("formatVersion", JsonPrimitive(2)).with("filters", filters).text())
        assertEquals(setOf("tags"), v2.filters?.extras?.keys)
        val moved = v2.withVersion(3)
        assertEquals(3, moved.formatVersion)
        assertEquals(emptySet(), moved.filters?.extras?.keys)
        val withTags = moved.copy(filters = moved.filters?.copy(tags = listOf("Tag A")))
        assertEquals(3, withTags.requiredVersion)
        assertEquals(RulesWriter.write(withTags), RulesWriter.write(readOk(RulesWriter.write(withTags))))
    }

    private fun emptyFilters() = Filters(null, null, null, null, null, null)
}
