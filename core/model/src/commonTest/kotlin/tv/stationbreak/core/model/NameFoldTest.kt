// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class NameFoldTest {
    @Test
    fun foldsOnlyAsciiCapitals() {
        assertEquals("abc-xyz 09", foldNameAscii("ABC-XYZ 09"))
        assertEquals("\u00C9\u0130\u212A", foldNameAscii("\u00C9\u0130\u212A"))
    }

    private fun chars(vararg codes: Int) = codes.joinToString("") { Char(it).toString() }

    @Test
    fun aStudioNameLosesControlsFormatCharactersAndLoneSurrogatesAndTrims() {
        assertEquals("Pixar", cleanStudioName("Pix" + chars(0x202E, 0x200B, 7, 0x85, 0xFEFF, 0xD800) + "ar"))
        assertEquals("Pixar", cleanStudioName(" " + chars(0x200B) + "Pixar" + chars(0xDC00) + " "))
        assertEquals("", cleanStudioName(chars(0x202E, 0x200B, 0x2066, 0xFEFF)))
    }

    // The newer entries of the fixed table: a platform with older Unicode data must strip them too.
    @Test
    fun aStudioNameLosesTheNewerFormatCharactersFromTheFixedTable() {
        val supplementary = charArrayOf(Char(0xD80D), Char(0xDC30)).concatToString()
        assertEquals("ab", cleanStudioName("a" + chars(0x0890, 0x08E2, 0x180E) + "b"))
        assertEquals("ab", cleanStudioName("a" + supplementary + "b"))
        assertEquals("a" + chars(0x2065) + "b", cleanStudioName("a" + chars(0x2065) + "b"))
    }

    @Test
    fun aStudioNameKeepsJoinersAndMapsLineAndTabCharactersToASpace() {
        assertEquals("A" + chars(0x200D, 0x200C) + "B", cleanStudioName("A" + chars(0x200D, 0x200C) + "B"))
        assertEquals("A B  C", cleanStudioName("A" + chars(9) + "B" + chars(13, 10) + "C"))
        assertEquals("A  B", cleanStudioName("A" + chars(0x2029, 0x2028) + "B"))
    }

    @Test
    fun aStudioNameKeepsAWellFormedSurrogatePairAndIsIdempotent() {
        val pair = "x" + chars(0xD83C, 0xDFAC) + "y"
        assertEquals(pair, cleanStudioName(pair))
        val once = cleanStudioName(" A" + chars(0x200B, 9) + "B ")
        assertEquals(once, cleanStudioName(once))
    }

    @Test
    fun aTextWithNoCapitalIsReturnedAsItIs() {
        val text = "comedy"
        assertSame(text, foldNameAscii(text))
    }

    @Test
    fun foldedNamesKeepOrderAndTheListWhenNothingChanges() {
        assertEquals(listOf("comedy", "sci-fi", "x"), foldNames(listOf("comedy", "Sci-Fi", "X")))
        val plain = listOf("a", "b")
        assertSame(plain, foldNames(plain))
        assertEquals(emptyList(), foldNames(emptyList()))
    }
}
