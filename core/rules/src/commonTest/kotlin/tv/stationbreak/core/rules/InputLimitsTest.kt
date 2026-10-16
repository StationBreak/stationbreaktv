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

    @Test
    fun anUnpairedSurrogateMakesTheFileInvalid() {
        val escaped = """{"formatVersion":1,"seed":"1","name":"a\ud800b","sources":[{"type":"library","id":"$PLACEHOLDER_ID"}],"timing":{"broadcastDay":{"utcOffsetMinutes":0}}}"""
        invalid(escaped)
        invalid(escaped.replace("\\ud800", "\\udc00"))
        val inExtras = """{"formatVersion":1,"seed":"1","\ud800":1,"sources":[{"type":"library","id":"$PLACEHOLDER_ID"}],"timing":{"broadcastDay":{"utcOffsetMinutes":0}}}"""
        invalid(inExtras)
    }

    @Test
    fun aPairOfSurrogateEscapesIsOneCharacter() {
        val pair = """{"formatVersion":1,"seed":"1","name":"😀","sources":[{"type":"library","id":"$PLACEHOLDER_ID"}],"timing":{"broadcastDay":{"utcOffsetMinutes":0}}}"""
        assertEquals("😀", readOk(pair).name)
    }
}
