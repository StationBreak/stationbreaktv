// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CanonicalIdInRulesTest {
    private val badIds =
        listOf(
            "11111111-1111-1111-1111-111111111111",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "{11111111111111111111111111111111}",
            "1111111111111111111111111111111",
            "111111111111111111111111111111111",
            "gggggggggggggggggggggggggggggggg",
            "",
        )

    private fun idPlaces(id: String): Map<String, JsonObject> {
        val bad = { type: String -> source(type, "id" to JsonPrimitive(id)) }
        val filler = { obj("mode" to JsonPrimitive("none"), "source" to bad("collection")) }
        return mapOf(
            "sources[0].id" to minimalRules().with("sources", array(bad("library"))),
            "sources[1].id" to minimalRules().with("sources", array(libraryJson(), bad("collection"))),
            "sources[0].id (series)" to minimalRules().with("sources", array(bad("series"))),
            "exclusions[0].id" to minimalRules().with("exclusions", array(bad("library"))),
            "exclusions[0].id (series)" to minimalRules().with("exclusions", array(bad("series"))),
            "filler.source.id" to minimalRules().with("filler", filler()),
        )
    }

    @Test
    fun rejectsABadIdInEveryPlaceAnIdCanAppearAndNamesTheField() {
        for (id in badIds) {
            for ((place, rules) in idPlaces(id)) {
                val result = RulesReader.read(rules.text())
                assertIs<RulesResult.Invalid>(result, "'$id' in $place was accepted: $result")
                val field = place.substringBefore(" ")
                assertEquals(true, result.reason.contains(field), "$place: ${result.reason}")
                assertEquals(false, id.isNotEmpty() && result.reason.contains(id), "the reason echoes the ID")
            }
        }
    }

    @Test
    fun rejectsAnIdThatIsNotAString() {
        val rules = minimalRules().with("sources", array(source("library", "id" to JsonPrimitive(11111))))
        assertIs<RulesResult.Invalid>(RulesReader.read(rules.text()))
    }

    @Test
    fun theReaderDoesNotRepairAnId() {
        val result = RulesReader.read(ExampleFiles.invalid.getValue("uppercase-id.json"))
        assertIs<RulesResult.Invalid>(result)
    }

    @Test
    fun acceptsACanonicalIdInEveryPlace() {
        for ((place, rules) in idPlaces(PLACEHOLDER_ID)) {
            assertIs<RulesResult.Ok>(RulesReader.read(rules.text()), place)
        }
    }
}
