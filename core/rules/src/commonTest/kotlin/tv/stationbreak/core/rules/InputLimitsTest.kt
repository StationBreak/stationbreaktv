// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InputLimitsTest {
    private fun invalid(text: String): RulesResult.Invalid {
        val result = RulesReader.read(text)
        assertIs<RulesResult.Invalid>(result, "expected Invalid, got ${result::class.simpleName}")
        return result
    }

    /** Wraps a number in [levels] lists. The result sits inside the top-level object, so it adds [levels] to the depth. */
    private fun nested(levels: Int): JsonElement {
        var text = "0"
        repeat(levels) { text = "[$text]" }
        return Json.parseToJsonElement(text)
    }

    private fun sources(count: Int) = JsonArray(List(count) { libraryJson() })

    @Test
    fun aFileJustUnderTheSizeLimitIsRead() {
        val base = minimalRules().with("note", "").text()
        val padding = RulesLimits.MAX_BYTES - base.encodeToByteArray().size
        val text = minimalRules().with("note", "x".repeat(padding)).text()
        assertEquals(RulesLimits.MAX_BYTES, text.encodeToByteArray().size)
        assertIs<RulesResult.Ok>(RulesReader.read(text))
    }

    @Test
    fun aFileOverTheSizeLimitFailsPlainly() {
        val base = minimalRules().with("note", "").text()
        val padding = RulesLimits.MAX_BYTES - base.encodeToByteArray().size + 1
        val result = invalid(minimalRules().with("note", "x".repeat(padding)).text())
        assertEquals(true, result.reason.contains("256 KiB"))
    }

    @Test
    fun theSizeIsCountedInBytesNotCharacters() {
        // Each emoji is 4 bytes and 2 UTF-16 units, so this is well under the limit in characters and over it in bytes.
        val text = minimalRules().with("note", "😀".repeat(RulesLimits.MAX_BYTES / 4)).text()
        invalid(text)
    }

    @Test
    fun aNewerVersionFileOverTheSizeLimitIsStillInvalid() {
        invalid("""{"formatVersion":2,"note":"${"x".repeat(RulesLimits.MAX_BYTES)}"}""")
    }

    @Test
    fun nestingAtTheLimitIsReadAndOneMoreFails() {
        val atLimit = minimalRules().with("note", nested(RulesLimits.MAX_DEPTH - 1)).text()
        assertIs<RulesResult.Ok>(RulesReader.read(atLimit))
        val over = invalid(minimalRules().with("note", nested(RulesLimits.MAX_DEPTH)).text())
        assertEquals(true, over.reason.contains("nested"))
    }

    @Test
    fun aVeryDeepFileFailsPlainlyWithoutCrashing() {
        invalid("[".repeat(100_000) + "]".repeat(100_000))
        invalid("""{"formatVersion":2,"a":""" + "[".repeat(50_000))
    }

    @Test
    fun bracketsInsideStringsDoNotCountAsNesting() {
        val text = minimalRules().with("note", "[".repeat(100) + "{\\\"" + "]".repeat(100)).text()
        assertIs<RulesResult.Ok>(RulesReader.read(text))
    }

    @Test
    fun aSourceListAtTheLimitIsReadAndOneMoreFails() {
        assertIs<RulesResult.Ok>(RulesReader.read(minimalRules().with("sources", sources(RulesLimits.MAX_LIST_ENTRIES)).text()))
        invalid(minimalRules().with("sources", sources(RulesLimits.MAX_LIST_ENTRIES + 1)).text())
    }

    @Test
    fun anExclusionListAtTheLimitIsReadAndOneMoreFails() {
        assertIs<RulesResult.Ok>(RulesReader.read(minimalRules().with("exclusions", sources(RulesLimits.MAX_LIST_ENTRIES)).text()))
        invalid(minimalRules().with("exclusions", sources(RulesLimits.MAX_LIST_ENTRIES + 1)).text())
    }

    @Test
    fun aChannelNameIsCountedInCodePoints() {
        val emoji = "😀"
        assertIs<RulesResult.Ok>(RulesReader.read(minimalRules().with("name", emoji.repeat(100)).text()))
        invalid(minimalRules().with("name", emoji.repeat(101)).text())
        invalid(minimalRules().with("name", "").text())
        assertIs<RulesResult.Ok>(RulesReader.read(minimalRules().with("name", "a".repeat(100)).text()))
        invalid(minimalRules().with("name", "a".repeat(101)).text())
    }

    @Test
    fun aSourceNameIsCountedInCodePoints() {
        val emoji = "😀"

        fun genre(name: String) = minimalRules().with("sources", array(source("genre", "name" to JsonPrimitive(name)))).text()
        assertIs<RulesResult.Ok>(RulesReader.read(genre(emoji.repeat(256))))
        invalid(genre(emoji.repeat(257)))
        invalid(genre(""))
    }

    // Builds a file by hand so a JSON escape such as \\ud800 reaches the reader as written.
    private fun file(
        name: String,
        extraKey: String = "note",
    ): String =
        "{\"formatVersion\":1,\"seed\":\"1\",\"$extraKey\":1,\"name\":\"$name\"," +
            "\"sources\":[{\"type\":\"library\",\"id\":\"$PLACEHOLDER_ID\"}]," +
            "\"timing\":{\"broadcastDay\":{\"utcOffsetMinutes\":0}}}"

    @Test
    fun anUnpairedSurrogateMakesTheFileInvalid() {
        invalid(file("a\\ud800b"))
        invalid(file("a\\udc00b"))
        invalid(file("\\ud83d"))
        invalid(file("\\ude00\\ud83d"))
        invalid(file("ok", extraKey = "\\ud800"))
    }

    @Test
    fun aPairOfSurrogateEscapesIsOneCharacter() {
        assertEquals("\uD83D\uDE00", readOk(file("\\ud83d\\ude00")).name)
        assertEquals(1, RulesLimits.codePoints(readOk(file("\\ud83d\\ude00")).name.orEmpty()))
    }
}
