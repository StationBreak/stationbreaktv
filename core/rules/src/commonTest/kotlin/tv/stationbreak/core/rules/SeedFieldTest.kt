// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SeedFieldTest {
    private fun invalidReason(seed: kotlinx.serialization.json.JsonElement?): String {
        val rules = if (seed == null) minimalRules().without("seed") else minimalRules().with("seed", seed)
        val result = RulesReader.read(rules.text())
        assertIs<RulesResult.Invalid>(result, "seed $seed was accepted")
        return result.reason
    }

    @Test
    fun aMissingSeedIsAnInvalidFile() {
        assertTrue(invalidReason(null).contains("seed"))
    }

    @Test
    fun aMalformedSeedIsAnInvalidFile() {
        val bad =
            listOf(
                "007",
                "00",
                "-1",
                "+1",
                "abc",
                "",
                " 1",
                "1 ",
                "1.0",
                "0x1",
                "18446744073709551616",
                "1" + "0".repeat(30),
            )
        for (text in bad) assertTrue(invalidReason(JsonPrimitive(text)).contains("seed"), text)
    }

    @Test
    fun aSeedThatIsNotAStringIsInvalid() {
        val notStrings =
            listOf(JsonPrimitive(12345), JsonPrimitive(0), JsonPrimitive(true), JsonNull, JsonArray(emptyList()))
        for (element in notStrings) {
            assertTrue(invalidReason(element).contains("seed"), element.toString())
        }
        assertIs<RulesResult.Invalid>(RulesReader.read(ExampleFiles.invalid.getValue("seed-not-a-string.json")))
    }

    @Test
    fun aCanonicalSeedIsAcceptedAndWrittenBackAsRead() {
        for (text in listOf("0", "1", "9007199254740993", "13835058055282163712", "18446744073709551615")) {
            val rules = readOk(minimalRules().with("seed", text).text())
            assertEquals(text, rules.seed.format())
            assertTrue(RulesWriter.write(rules).contains("\"seed\": \"$text\""), text)
        }
    }

    @Test
    fun aSeedAboveTwoToTheFiftyThreeKeepsItsLowBits() {
        val text = "9007199254740993"
        assertEquals(text, readOk(minimalRules().with("seed", text).text()).seed.format())
    }
}
