// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// The format reads JSON as RFC 8259 narrowed by I-JSON (RFC 7493): strict numbers, no duplicate member names, valid
// UTF-8, no unpaired surrogates, no byte-order mark.
class JsonProfileTest {
    private val id = PLACEHOLDER_ID

    private fun file(
        version: String = "1",
        offset: String = "0",
        extra: String = "",
    ): String =
        """{"formatVersion":$version,"seed":"1","sources":[{"type":"library","id":"$id"}],""" +
            """"timing":{"broadcastDay":{"utcOffsetMinutes":$offset}}$extra}"""

    private fun assertInvalid(text: String) = assertIs<RulesResult.Invalid>(RulesReader.read(text), text)

    @Test
    fun anIntegerFieldTakesOnlyStrictJsonIntegers() {
        assertIs<RulesResult.Ok>(RulesReader.read(file(offset = "0")))
        assertIs<RulesResult.Ok>(RulesReader.read(file(offset = "-300")))
        val bad = listOf("01", "00", "+5", "1e2", "1E2", "1.0", "-0", "0.0", "NaN", "Infinity", "-Infinity", "0x10")
        for (offset in bad) assertInvalid(file(offset = offset))
    }

    @Test
    fun theVersionTakesOnlyAStrictJsonInteger() {
        for (bad in listOf("01", "+1", "1.0", "1e0", "NaN", "-0", "0")) assertInvalid(file(version = bad))
    }

    @Test
    fun otherIntegerFieldsAreStrictToo() {
        for (bad in listOf("01", "2.0", "+2", "2e0")) {
            val text = file().replace(""""broadcastDay"""", """"blockLength":$bad,"broadcastDay"""")
            assertInvalid(text)
        }
        val years = file().replace(""""type":"library","id":"$id"""", """"type":"yearRange","from":1990,"to":0200""")
        assertInvalid(years)
    }

    @Test
    fun aNumberAnywhereMustBeValidJson() {
        for (bad in listOf("NaN", "Infinity", "-Infinity", "01", "+5", "1.", ".5", "0x1")) {
            assertInvalid(file(extra = ""","note":$bad"""))
            assertInvalid(file(extra = ""","note":[$bad]"""))
        }
    }

    @Test
    fun validJsonNumbersInUnknownFieldsAreKept() {
        for (good in listOf("0", "1.5", "1e2", "-1.25E-3", "123456789012345678901234567890")) {
            val rules = readOk(file(extra = ""","note":$good"""))
            val written = Json.parseToJsonElement(RulesWriter.write(rules)) as JsonObject
            // A parser may re-encode a number (1e2 as 100.0): the value is kept, not the spelling.
            val kept = (written["note"] as JsonPrimitive).content.toDouble()
            assertEquals(good.toDouble(), kept, good)
        }
    }

    @Test
    fun aDuplicateMemberNameIsInvalidAtAnyLevel() {
        assertInvalid(file(extra = ""","seed":"2""""))
        assertInvalid(file().replace(""""type":"library"""", """"type":"library","type":"library""""))
        assertInvalid(file().replace(""""utcOffsetMinutes":0""", """"utcOffsetMinutes":0,"utcOffsetMinutes":0"""))
        assertInvalid(file(extra = ""","note":{"a":1,"b":{"c":1,"c":2}}"""))
        assertInvalid(file(extra = ""","note":[{"a":1,"a":1}]"""))
    }

    @Test
    fun namesThatDecodeToTheSameKeyAreDuplicates() {
        assertInvalid(file(extra = ",\"note\":1,\"\\u006eote\":2"))
        assertInvalid(file(extra = ",\"\\u00e9\":1,\"\u00e9\":2"))
        assertInvalid(file(extra = ",\"a\\/b\":1,\"a/b\":2"))
    }

    @Test
    fun theSameNameInDifferentObjectsIsFine() {
        val extra = ""","note":{"a":1},"other":{"a":2},"list":[{"a":1},{"a":1}]"""
        assertIs<RulesResult.Ok>(RulesReader.read(file(extra = extra)))
    }

    @Test
    fun aDuplicateNameInANewerFileIsStillInvalid() {
        assertInvalid(file(version = "2", extra = ""","note":1,"note":2"""))
    }

    @Test
    fun aKeyWithAnEscapedQuoteOrBackslashIsReadCorrectly() {
        assertIs<RulesResult.Ok>(RulesReader.read(file(extra = """,""" + "\"a\\\\\":1,\"a\\\"\":2,\"a\":3")))
        assertInvalid(file(extra = """,""" + "\"a\\\\\":1,\"a\\\\\":2"))
    }

    @Test
    fun aByteOrderMarkIsInvalid() {
        val bom = 0xFEFF.toChar().toString()
        assertInvalid(bom + file())
        assertInvalid(bom + file(version = "2"))
    }

    @Test
    fun bytesAreDecodedAsStrictUtf8() {
        assertIs<RulesResult.Ok>(RulesReader.read(file().encodeToByteArray()))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + file().encodeToByteArray()
        assertIs<RulesResult.Invalid>(RulesReader.read(bom))
        val badBytes = file(extra = ""","note":"x"""").encodeToByteArray()
        val broken =
            badBytes.copyOf().also {
                it[badBytes.size - 3] = 0xC3.toByte()
                it[badBytes.size - 2] = 0x28
            }
        assertIs<RulesResult.Invalid>(RulesReader.read(broken))
        val surrogate = byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())
        val head = file(extra = ""","note":"""").dropLast(1).encodeToByteArray()
        assertIs<RulesResult.Invalid>(RulesReader.read(head + surrogate + "\"}".encodeToByteArray()))
    }

    @Test
    fun bytesOverTheLimitAreRejectedBeforeDecoding() {
        val result = RulesReader.read(ByteArray(RulesLimits.MAX_BYTES + 1) { 0xFF.toByte() })
        assertIs<RulesResult.Invalid>(result)
        assertTrue(result.reason.contains("256 KiB"), result.reason)
    }

    @Test
    fun bytesAndTextGiveTheSameResultForValidFiles() {
        for ((name, text) in ExampleFiles.valid) {
            assertEquals(RulesReader.read(text), RulesReader.read(text.encodeToByteArray()), name)
        }
    }
}
