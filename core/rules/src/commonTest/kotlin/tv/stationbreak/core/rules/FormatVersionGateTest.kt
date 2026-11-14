// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FormatVersionGateTest {
    private fun read(version: String): RulesResult {
        val body = """{"formatVersion":$version,"sources":"not a list"}"""
        return RulesReader.read(body)
    }

    @Test
    fun aNewerVersionNeedsAnUpdateEvenWhenTheBodyIsUnreadable() {
        assertEquals(RulesResult.UpdateRequired(6), read("6"))
        assertEquals(RulesResult.UpdateRequired(2147483647), read("2147483647"))
        assertEquals(RulesResult.UpdateRequired(4294967296), read("4294967296"))
    }

    @Test
    fun aVersionTooBigForAnyIntegerStillNeedsAnUpdate() {
        assertIs<RulesResult.UpdateRequired>(read("99999999999999999999999"))
    }

    @Test
    fun aNewerVersionWithAValidBodyIsNotReadAnyFurther() {
        val text = minimalRules().with("formatVersion", JsonPrimitive(6)).with("somethingNew", "x").text()
        assertEquals(RulesResult.UpdateRequired(6), RulesReader.read(text))
    }

    @Test
    fun theCurrentVersionWithABodyOfTheWrongShapeIsInvalidNotUpdateRequired() {
        assertIs<RulesResult.Invalid>(read("1"))
    }

    @Test
    fun aMissingOrNonIntegerVersionIsInvalid() {
        val bad =
            listOf(
                "null",
                "\"1\"",
                "\"2\"",
                "1.5",
                "1.0",
                "2.0",
                "1e0",
                "true",
                "[]",
                "{}",
                "0",
                "-1",
                "-99999999999999999999999",
            )
        for (version in bad) assertIs<RulesResult.Invalid>(read(version), version)
        assertIs<RulesResult.Invalid>(RulesReader.read("""{"sources":[]}"""))
    }

    @Test
    fun textThatIsNotARulesObjectIsInvalid() {
        for (text in listOf("", "   ", "not json", "{", "[]", "null", "42", "\"x\"", "{\"formatVersion\":2")) {
            assertIs<RulesResult.Invalid>(RulesReader.read(text), text)
        }
    }

    @Test
    fun theSupportedVersionIsFive() {
        assertEquals(5L, RulesLimits.SUPPORTED_FORMAT_VERSION)
        assertEquals(5, SpreadDayEnd.FORMAT_VERSION)
        assertIs<RulesResult.Ok>(RulesReader.read(minimalRules().with("formatVersion", JsonPrimitive(5)).text()))
        assertIs<RulesResult.Ok>(RulesReader.read(minimalRules().with("formatVersion", JsonPrimitive(4)).text()))
        assertIs<RulesResult.Ok>(RulesReader.read(minimalRules().with("formatVersion", JsonPrimitive(3)).text()))
        assertIs<RulesResult.Ok>(RulesReader.read(minimalRules().text()))
        assertIs<RulesResult.Ok>(RulesReader.read(minimalRules().with("formatVersion", JsonPrimitive(2)).text()))
    }

    // Version 5 (the spread day end) is a behaviour version: it knows the fields of version 4 and nothing more.
    @Test
    fun aVersion5FileIsReadAndHeldAt5() {
        val curated = obj("mode" to JsonPrimitive("curated"), "repeatGapMinutes" to JsonPrimitive(60))
        val text =
            minimalRules()
                .with("formatVersion", JsonPrimitive(5))
                .with("ordering", curated)
                .with("filters", obj("tags" to array(JsonPrimitive("Tag A"))))
                .with("futureField", "x")
                .text()
        val rules = readOk(text)
        assertEquals(5, rules.formatVersion)
        assertEquals(3, rules.requiredVersion, "requiredVersion never reports 5")
        assertEquals(60, rules.ordering?.repeatGapMinutes)
        assertEquals(setOf("futureField"), rules.extras.keys)
        val written = RulesWriter.write(rules)
        assertEquals(5, readOk(written).formatVersion)
        assertEquals(written, RulesWriter.write(readOk(written)), "byte for byte")
        assertEquals(5, readOk(minimalRules().with("formatVersion", JsonPrimitive(5)).text()).formatVersion)
    }

    @Test
    fun aVersion5FileKnowsExactlyTheFieldsOfVersion4() {
        assertEquals(RulesVocabulary.topLevel(4), RulesVocabulary.topLevel(5))
        assertEquals(RulesVocabulary.ordering(4), RulesVocabulary.ordering(5))
        assertEquals(RulesVocabulary.filters(4), RulesVocabulary.filters(5))
        assertEquals(RulesVocabulary.filterRuntime(4), RulesVocabulary.filterRuntime(5))
    }

    @Test
    fun onlyAVersion5FileSpreadsTheDayEnd() {
        assertTrue(readOk(minimalRules().with("formatVersion", JsonPrimitive(5)).text()).spreadsDayEnd)
        for (version in 1..4) {
            assertFalse(readOk(minimalRules().with("formatVersion", JsonPrimitive(version)).text()).spreadsDayEnd)
        }
    }

    @Test
    fun aVersion6FileIsNewer() {
        val text = minimalRules().with("formatVersion", JsonPrimitive(6)).text()
        assertEquals(RulesResult.UpdateRequired(6), RulesReader.read(text))
    }

    @Test
    fun anInvalidReasonHoldsNoFileContent() {
        val result = RulesReader.read("""{"formatVersion":1,"seed":"SECRET-TOKEN","sources":[]}""")
        assertIs<RulesResult.Invalid>(result)
        assertEquals(false, result.reason.contains("SECRET-TOKEN"))
    }
}
