// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FormatVersionGateTest {
    private fun read(version: String): RulesResult {
        val body = """{"formatVersion":$version,"sources":"not a list"}"""
        return RulesReader.read(body)
    }

    @Test
    fun aNewerVersionNeedsAnUpdateEvenWhenTheBodyIsUnreadable() {
        assertEquals(RulesResult.UpdateRequired(2), read("2"))
        assertEquals(RulesResult.UpdateRequired(2147483647), read("2147483647"))
        assertEquals(RulesResult.UpdateRequired(4294967296), read("4294967296"))
    }

    @Test
    fun aVersionTooBigForAnyIntegerStillNeedsAnUpdate() {
        assertIs<RulesResult.UpdateRequired>(read("99999999999999999999999"))
    }

    @Test
    fun aNewerVersionWithAValidBodyIsNotReadAnyFurther() {
        val text = minimalRules().with("formatVersion", JsonPrimitive(2)).with("somethingNew", "x").text()
        assertEquals(RulesResult.UpdateRequired(2), RulesReader.read(text))
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
    fun theSupportedVersionIsOne() {
        assertEquals(1L, RulesLimits.SUPPORTED_FORMAT_VERSION)
        assertIs<RulesResult.Ok>(RulesReader.read(minimalRules().text()))
    }

    @Test
    fun anInvalidReasonHoldsNoFileContent() {
        val result = RulesReader.read("""{"formatVersion":1,"seed":"SECRET-TOKEN","sources":[]}""")
        assertIs<RulesResult.Invalid>(result)
        assertEquals(false, result.reason.contains("SECRET-TOKEN"))
    }
}
