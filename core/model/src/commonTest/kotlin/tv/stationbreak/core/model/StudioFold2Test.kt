// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Studio fold 2 (ADR-0026) against `docs/rules-format/vectors/studio-fold-2.json` and its properties. */
class StudioFold2Test {
    private val file = Json.parseToJsonElement(StudioFold2VectorFile.json).jsonObject
    private val vectors = file.getValue("vectors").jsonArray.map { it.jsonObject }

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    private fun fold1(text: String) = foldNameAscii(cleanStudioName(text))

    private fun show(text: String) = text.map { "U+" + it.code.toString(HEX).padStart(4, '0') }.toString()

    @Test
    fun everyVectorGivesItsKey() {
        assertTrue(vectors.size >= MIN_VECTORS, "vectors: ${vectors.size}")
        for (row in vectors) {
            assertEquals(show(row.text("key")), show(studioKey2(row.text("input"))), row.text("name"))
        }
    }

    // The file gives fold 1 of each input too, so a port can check the second property; ours must agree with it.
    @Test
    fun everyVectorGivesItsFoldOneAnswer() {
        for (row in vectors) {
            assertEquals(show(row.text("fold1")), show(fold1(row.text("input"))), row.text("name"))
        }
    }

    @Test
    fun foldTwoIsIdempotentOverEveryVector() {
        for (row in vectors) {
            val key = studioKey2(row.text("input"))
            assertEquals(show(key), show(studioKey2(key)), row.text("name"))
        }
    }

    @Test
    fun foldTwoOfFoldOneIsFoldTwoOverEveryVector() {
        for (row in vectors) {
            val input = row.text("input")
            assertEquals(show(studioKey2(input)), show(studioKey2(fold1(input))), row.text("name"))
        }
    }

    @Test
    fun theReportedCasesAllMatchWaltDisney() {
        val key = studioKey2("Walt Disney")
        assertEquals("waltdisney", key)
        for (spelling in listOf(
            "Walt\u2009\u200DDisney",
            "\u3000\u200DWalt Disney",
            "Walt\u3000\u200DDisney",
            "Walt\u3164Disney",
            "Walt\u000BDisney",
            "Walt\u0085Disney",
            "Walt  Disney",
            "WaltDisney",
        )) {
            assertEquals(key, studioKey2(spelling), show(spelling))
        }
    }

    // The tables in the code are exactly the sets the file lists: every code point, so a typo in a range shows.
    @Test
    fun theTablesAreTheSetsOfTheFile() {
        val sets = file.getValue("sets").jsonObject
        val dropped = sets.getValue("D").jsonObject
        val removed =
            ranges(sets.getValue("S").jsonArray) + ranges(sets.getValue("B").jsonArray) +
                ranges(dropped.getValue("fold1Dropped").jsonArray) +
                ranges(dropped.getValue("defaultIgnorable").jsonArray) + ranges(dropped.getValue("extra").jsonArray)
        val gap = ranges(sets.getValue("G").jsonArray)
        val latin = ranges(sets.getValue("L").jsonArray)
        for (cp in 0..MAX_CODE_POINT) {
            assertEquals(removed.any { cp in it }, isRemovedByStudioKey2(cp), "R U+" + cp.toString(HEX))
            assertEquals(gap.any { cp in it }, isGapForStudioKey2(cp), "G U+" + cp.toString(HEX))
            assertEquals(latin.any { cp in it }, isLatinForStudioKey2(cp), "L U+" + cp.toString(HEX))
        }
    }

    // ADR-0026, Properties: fold 1's dropped, mapped and trimmed sets are inside R; G holds its mapped and trimmed sets
    // and nothing of its dropped set. Read from fold 1's behaviour on every code point, not from its tables.
    @Test
    fun everythingFoldOneDropsMapsOrTrimsIsRemovedByFoldTwo() {
        for (cp in 0..MAX_CODE_POINT) {
            if (cp in SURROGATES) continue
            val c = codePoint(cp)
            val inside = cleanStudioName("a" + c + "b")
            val name = "U+" + cp.toString(HEX)
            when (inside) {
                "ab" -> {
                    assertTrue(isRemovedByStudioKey2(cp), "fold 1 drops $name")
                    assertFalse(isGapForStudioKey2(cp), "fold 1 drops $name, so it is not in G")
                }

                "a b" -> {
                    if (cp != ' '.code) {
                        assertTrue(isRemovedByStudioKey2(cp), "fold 1 maps $name")
                        assertTrue(isGapForStudioKey2(cp), "fold 1 maps $name")
                    }
                }

                else -> {
                    assertEquals("a" + c + "b", inside, name)
                }
            }
            if (inside != "ab" && cleanStudioName(c + "a") == "a") {
                assertTrue(isRemovedByStudioKey2(cp), "fold 1 trims $name")
                assertTrue(isGapForStudioKey2(cp), "fold 1 trims $name")
            }
        }
        assertTrue(isRemovedByStudioKey2(' '.code) && isGapForStudioKey2(' '.code))
    }

    // The two properties over random names drawn from the code points the pass treats specially.
    @Test
    fun thePropertiesHoldOverRandomNames() {
        val random = Random(RANDOM_SEED)
        val alphabet =
            listOf(
                "a",
                "Z",
                "e\u0301",
                "\u024F",
                "\u0250",
                "\u036F",
                "\u0370",
                "\u6771",
                "\u0915",
                "\u094D",
                "\u200C",
                "\u200D",
                " ",
                "\u00A0",
                "\u3000",
                "\u2009",
                "\t",
                "\n",
                "\r",
                "\u000B",
                "\u000C",
                "\u001C",
                "\u0085",
                "\u2028",
                "\u2029",
                "\u115F",
                "\u3164",
                "\u2800",
                "\uFFA0",
                "\u034F",
                "\u200B",
                "\u2065",
                "\uFE0F",
                "\u00AD",
                "\uD800",
                "\uDC00",
                "\uDB40\uDC02",
                "\uDB40\uDC01",
                "\uD83C\uDFAC",
            )
        repeat(RANDOM_NAMES) {
            val name = buildString { repeat(random.nextInt(MAX_RANDOM_LENGTH)) { append(alphabet.random(random)) } }
            val key = studioKey2(name)
            assertEquals(show(key), show(studioKey2(key)), show(name))
            assertEquals(show(key), show(studioKey2(fold1(name))), show(name))
        }
    }

    @Test
    fun aKeyListDropsEmptyKeysAndKeepsOrder() {
        assertEquals(listOf("pixar", "waltdisney"), studioKeys2(listOf("Pixar", "\u3164", "Walt\u00A0Disney", "")))
    }

    private fun ranges(array: JsonArray): List<IntRange> =
        array.map { pair ->
            val (low, high) = pair.jsonArray.map { it.jsonPrimitive.content.toInt(HEX) }
            low..high
        }

    private fun codePoint(cp: Int): String =
        if (cp < SUPPLEMENTARY) {
            Char(cp).toString()
        } else {
            val offset = cp - SUPPLEMENTARY
            charArrayOf(Char(HIGH + (offset shr TEN)), Char(LOW + (offset and LOW_MASK))).concatToString()
        }

    private companion object {
        const val HEX = 16
        const val MIN_VECTORS = 300
        const val MAX_CODE_POINT = 0x10FFFF
        const val SUPPLEMENTARY = 0x10000
        const val HIGH = 0xD800
        const val LOW = 0xDC00
        const val TEN = 10
        const val LOW_MASK = 0x3FF
        const val RANDOM_SEED = 26
        const val RANDOM_NAMES = 20_000
        const val MAX_RANDOM_LENGTH = 12
        val SURROGATES = 0xD800..0xDFFF
    }
}
