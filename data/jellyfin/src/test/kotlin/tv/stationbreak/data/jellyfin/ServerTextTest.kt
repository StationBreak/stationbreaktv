// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hostile characters are built from code points, not written as `\u` escapes: the formatter rewrites an escape into
 * the raw, invisible character.
 */
class ServerTextTest {
    private fun ch(code: Int) = String(Character.toChars(code))

    private fun overview(raw: String) = ServerText.overview(raw)

    @Test
    fun hostileInputIsCutBeforeTheRegexesRun() {
        val hostile = "x".repeat(1_000_000)

        val bounded = ServerText.bound(hostile, ServerText.OVERVIEW_MAX)

        assertTrue(bounded.length < hostile.length / 50, "cut to ${bounded.length}")
        assertEquals(ServerText.OVERVIEW_MAX, overview(hostile)?.length)
        assertTrue(ServerText.bound(hostile, ServerText.GENRE_MAX).length <= ServerText.GENRE_MAX * 8)
    }

    // Not a wall-clock benchmark: on a shared, niced host a tight limit flakes. The limit is generous (5 s), so it
    // only catches a regex that has gone super-linear (which would take minutes), and the cut above bounds the work.
    @Test
    fun hostileAngleBracketsDoNotBlowUp() {
        val hostile = "<".repeat(1_000_000)

        val elapsed = measureTimeMillis { ServerText.overview(hostile) }
        val genre = measureTimeMillis { ServerText.genres(listOf(hostile)) }

        assertTrue(elapsed < 5_000, "overview took $elapsed ms")
        assertTrue(genre < 5_000, "genre took $genre ms")
    }

    @Test
    fun paragraphsKeepALineBreak() {
        assertEquals("One\nTwo", overview("<p>One</p><p>Two</p>"))
        assertEquals("One\nTwo", overview("<DIV>One</DIV><div>Two</Div >"))
        assertEquals("A\n\nB", overview("<ul><li>A</li><LI class=\"x\">B</li></ul>"))
        assertEquals(listOf("Sci Fi"), ServerText.genres(listOf("Sci</p>Fi")))
    }

    @Test
    fun blankLineRunsCollapse() {
        assertEquals("a\n\nb", overview("a\n\n\n\n\nb"))
        assertEquals("a\n\nb", overview("a<br><br><br>b"))
        assertEquals("a\nb", overview("a\nb"))
    }

    @Test
    fun supplementaryTagCharactersAreStripped() {
        val tags = listOf(0xE0001, 0xE0020, 0xE0041, 0xE007F).joinToString("") { ch(it) }

        assertEquals("ab", overview("a${tags}b"))
    }

    @Test
    fun supplementaryCombiningMarksAreCapped() {
        // U+1D167 is a non-spacing mark above U+FFFF.
        val marks = ch(0x1D167).repeat(50)

        assertEquals("a" + ch(0x1D167).repeat(ServerText.MARKS_MAX) + "b", overview("a${marks}b"))
    }

    @Test
    fun unpairedSurrogatesAreDropped() {
        val high = 0xD83D.toChar()
        val low = 0xDE00.toChar()

        assertEquals("ab", overview("a${high}b"))
        assertEquals("ab", overview("a${low}b"))
        assertEquals("a${ch(0x1F600)}b", overview("a${high}${low}b"))
        // The pre-cut can leave half of a pair at the end.
        val cutThroughPair = "x".repeat(ServerText.OVERVIEW_MAX * 8 - 1) + ch(0x1F600)
        val result = overview(cutThroughPair)
        assertTrue(result != null && result.none { it.isSurrogate() })
    }

    @Test
    fun stripsDirectionControlsAndFormatCharacters() {
        val formats = listOf(0x202A, 0x202E, 0x2066, 0x2069, 0x200E, 0x200F, 0x061C, 0x206A, 0x206F, 0xFEFF, 0x200B)

        for (code in formats) {
            assertEquals("ab", overview("a${ch(code)}b"), "U+" + code.toString(16))
        }
    }

    @Test
    fun keepsZeroWidthJoinerAndNonJoiner() {
        assertEquals("a${ch(0x200D)}b${ch(0x200C)}c", overview("a${ch(0x200D)}b${ch(0x200C)}c"))
    }

    @Test
    fun lineAndParagraphSeparatorsBecomeNewLinesInTheOverviewAndGoFromGenres() {
        assertEquals("a\nb\nc", overview("a${ch(0x2028)}b${ch(0x2029)}c"))
        assertEquals(listOf("ab"), ServerText.genres(listOf("a${ch(0x2028)}b")))
    }

    @Test
    fun tabAndCarriageReturnBecomeASpaceAndCrlfIsOneNewLine() {
        assertEquals("a b c", overview("a\tb\rc"))
        assertEquals("a\nb", overview("a\r\nb"))
    }

    @Test
    fun entitiesCannotSmuggleCharactersPast() {
        assertEquals("ab", overview("a&#x202E;b"))
        assertEquals("ab", overview("a&#0;b"))
        assertEquals("a\nb", overview("a&#x2028;b"))
        assertEquals("ab", overview("a&#xD800;b"))
        assertEquals("ab", overview("a&#1114112;b"))
        assertEquals("a&lt;b", overview("a&amp;lt;b"))
    }

    @Test
    fun capNeverSplitsASurrogatePair() {
        val emoji = ch(0x1F600)
        val text = "x".repeat(ServerText.OVERVIEW_MAX - 1) + emoji

        val result = overview(text)

        assertEquals("x".repeat(ServerText.OVERVIEW_MAX - 1), result)
    }

    @Test
    fun genreWithBreakGetsASpaceNotGluedWords() {
        assertEquals(listOf("Sci Fi"), ServerText.genres(listOf("Sci<br>Fi")))
    }

    @Test
    fun genresAreTakenLazilyBlanksDroppedAndCapped() {
        val genres = listOf("", "  ", "A", "B", "C", "D", "E", "F")

        assertEquals(listOf("A", "B", "C", "D", "E"), ServerText.genres(genres))
    }

    @Test
    fun runsOfCombiningMarksAreCapped() {
        val marks = ch(0x0301).repeat(50)

        assertEquals("a" + ch(0x0301).repeat(ServerText.MARKS_MAX) + "b", overview("a${marks}b"))
    }

    @Test
    fun nothingLeftIsNull() {
        assertNull(overview("<p></p> ${ch(0x202E)}"))
    }
}
