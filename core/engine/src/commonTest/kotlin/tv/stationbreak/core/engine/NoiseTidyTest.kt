// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/** The code points `NoiseTidy` reads as a space or a dash (#1088), and the ones just outside each range, as escapes. */
class NoiseTidyTest {
    private val spaces = listOf(0x09, 0x0A, 0x0D, 0xA0, 0x1680) + (0x2000..0x200A) + listOf(0x202F, 0x205F, 0x3000)
    private val dashes = (0x2010..0x2015) + listOf(0x2212, 0xFE58, 0xFE63, 0xFF0D)

    // Each is next to a listed range: the ones before, between and after (ZWSP, ZWNJ, bar variants, small-form signs).
    private val outside =
        listOf(
            '\u1FFF',
            '\u200B',
            '\u200C',
            '\u2016',
            '\u2211',
            '\u2213',
            '\uFE57',
            '\uFE59',
            '\uFE62',
            '\uFE64',
            '\uFF0C',
            '\uFF0E',
            '\u00A1',
            '\u167F',
            '\u1681',
            '\u202E',
            '\u2030',
            '\u205E',
            '\u2060',
            '\u2FFF',
            '\u3001',
        )

    private fun around(code: Int) = "a" + code.toChar() + "b"

    @Test
    fun everyListedSpaceBecomesASpaceAndEveryListedDashAHyphen() {
        for (code in spaces) assertEquals("a b", NoiseTidy.tidy(around(code)), "U+" + code.toString(16))
        for (code in dashes) assertEquals("a-b", NoiseTidy.tidy(around(code)), "U+" + code.toString(16))
    }

    @Test
    fun aRatingTidyTakesTheDashesButNotTheSpacesOtherThanU0020() {
        for (code in dashes) assertEquals("a-b", NoiseTidy.tidy(around(code), anySpace = false))
        for (code in spaces) assertEquals(around(code), NoiseTidy.tidy(around(code), anySpace = false))
        assertEquals("a b", NoiseTidy.tidy("  a    b  ", anySpace = false))
    }

    @Test
    fun theCodePointsJustOutsideEachRangeAreUntouched() {
        for (char in outside) {
            val text = "a" + char + "b"
            assertEquals(text, NoiseTidy.tidy(text), "U+" + char.code.toString(16))
        }
    }
}
