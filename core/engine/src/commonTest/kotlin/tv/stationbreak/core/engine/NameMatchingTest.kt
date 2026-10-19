// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/rules-format.md, "String matching": ASCII A to Z fold, exact code units for everything else, no trimming. */
class NameMatchingTest {
    private fun nameSet(vararg names: String) = NameSet(names.toList(), IntArray(names.size) { it })

    private fun matches(
        rule: String,
        server: String,
    ) = nameSet(rule).contains(server)

    @Test
    fun caseOfTheAsciiLettersDoesNotMatter() {
        assertTrue(matches("Comedy", "comedy"))
        assertTrue(matches("Comedy", "COMEDY"))
        assertTrue(matches("comedy", "Comedy"))
    }

    @Test
    fun foldAsciiMapsOnlyAToZ() {
        assertEquals("abc-xyz 09", foldAscii("ABC-XYZ 09"))
        assertEquals("@[`{", foldAscii("@[`{"))
        assertEquals("ÉİK", foldAscii("ÉİK"))
    }

    @Test
    fun noTrimming() {
        assertFalse(matches("Sci-Fi", "Sci-Fi "))
        assertFalse(matches("Sci-Fi ", "Sci-Fi"))
        assertTrue(matches("Sci-Fi ", "sci-fi "))
    }

    @Test
    fun noNormalisationNfcAndNfdDiffer() {
        assertFalse(matches("café", "café"))
        assertTrue(matches("café", "CAFé"))
        assertFalse(matches("café", "CAFÉ"))
    }

    @Test
    fun theTurkishIsDoNotFoldToTheAsciiOnes() {
        assertFalse(matches("İ", "I"))
        assertFalse(matches("İ", "i"))
        assertFalse(matches("ı", "I"))
        assertFalse(matches("ı", "i"))
        assertFalse(matches("I", "ı"))
        assertFalse(matches("i", "İ"))
    }

    @Test
    fun theKelvinSignIsNotK() {
        assertFalse(matches("K", "k"))
        assertFalse(matches("K", "K"))
        assertFalse(matches("k", "K"))
    }

    @Test
    fun sharpSIsNotSS() {
        assertFalse(matches("ß", "SS"))
        assertFalse(matches("ß", "ss"))
        assertFalse(matches("straße", "STRASSE"))
    }

    @Test
    fun anEmptyItemNameNeverMatches() {
        assertFalse(matches("Comedy", ""))
        assertFalse(nameSet("Comedy", "Drama").contains(""))
    }

    @Test
    fun anEmojiAndANonLatinNameMatchOnlyExactly() {
        assertTrue(matches("😀 fun", "😀 FUN"))
        assertFalse(matches("😀", "😁"))
        assertTrue(matches("драма", "драма"))
        assertFalse(matches("драма", "ДРАМА"))
    }

    @Test
    fun aSetMatchesAnyOfItsNames() {
        val set = nameSet("Comedy", "Drama")
        assertTrue(set.contains("DRAMA"))
        assertTrue(set.contains("comedy"))
        assertFalse(set.contains("Action"))
    }

    @Test
    fun theSetReturnsTheLabelsItWasGiven() {
        val labeled = NameSet(listOf("Comedy", "Drama", "COMEDY"), intArrayOf(4, 7, 9))
        assertEquals(listOf(4, 9), labeled.indicesOf("comedy")?.toList())
    }

    @Test
    fun theSetRemembersWhichRuleEachNameCameFrom() {
        val set = nameSet("Comedy", "Drama", "COMEDY")
        assertEquals(listOf(0, 2), set.indicesOf("comedy")?.toList())
        assertEquals(listOf(1), set.indicesOf("DRAMA")?.toList())
        assertEquals(null, set.indicesOf("Action"))
    }
}
