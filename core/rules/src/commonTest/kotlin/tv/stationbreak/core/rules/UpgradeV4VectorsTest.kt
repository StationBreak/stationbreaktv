// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import tv.stationbreak.core.rules.VectorJson.array
import tv.stationbreak.core.rules.VectorJson.objects
import tv.stationbreak.core.rules.VectorJson.string
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/rules-format/vectors/upgrade-v4.json` (ADR-0026, Versioning) under both gate settings: a read upgrades a studio
 * file that says 3 only once the gate has passed, a save writes at the write version, and doing either twice changes
 * nothing more.
 */
class UpgradeV4VectorsTest {
    private val cases = VectorJson.parse(UpgradeV4VectorFile.json).array("cases").objects()

    private fun text(o: JsonElement) = Json.encodeToString(JsonElement.serializer(), o)

    private fun apply(
        operation: String,
        input: String,
        gatePassed: Boolean,
    ): JsonObject {
        val rules = readOk(input)
        val out =
            when (operation) {
                "read" -> rules.upgradedToStudioFold2(gatePassed)
                "save" -> rules.normalised(gatePassed)
                else -> error("unknown operation $operation")
            }
        return VectorJson.parse(RulesWriter.write(out))
    }

    @Test
    fun theFileHasTheCasesTheAdrAsks() {
        assertTrue(cases.size >= 10, "at least 10 cases, has ${cases.size}")
        assertEquals(cases.size, cases.map { it.string("name") }.toSet().size, "case names are unique")
        assertTrue(cases.any { it.string("operation") == "save" })
    }

    @Test
    fun everyCaseGivesItsOutputBeforeAndAfterTheGate() {
        for (case in cases) {
            val name = case.string("name")
            val input = text(case.getValue("input"))
            for ((gate, key) in listOf(false to "beforeGate", true to "afterGate")) {
                val once = apply(case.string("operation"), input, gate)
                assertEquals(case.getValue(key), once, "$key: $name")
                val twice = apply(case.string("operation"), text(once), gate)
                assertEquals(once, twice, "$key, done twice: $name")
            }
        }
    }

    @Test
    fun theAppDefaultIsBeforeTheGate() {
        for (case in cases.filter { it.string("operation") == "read" }) {
            val rules = readOk(text(case.getValue("input")))
            val expected = case.getValue("beforeGate")
            val written = VectorJson.parse(RulesWriter.write(rules.upgradedToStudioFold2()))
            assertEquals(expected, written, case.string("name"))
        }
    }
}
