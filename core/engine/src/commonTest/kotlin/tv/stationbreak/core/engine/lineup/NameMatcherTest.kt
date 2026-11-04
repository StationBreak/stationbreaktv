// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.movie
import tv.stationbreak.core.engine.snapshot
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.RuleItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NameMatcherTest {
    private fun fold(name: String) = NameMatcher.fold(name)

    @Test
    fun exactPrefixContainsAndWordOnFoldedNames() {
        assertTrue(NameMatcher.Exact("Show A").matches(fold("SHOW a")))
        assertFalse(NameMatcher.Exact("Show A").matches(fold("Show A 2")))
        assertTrue(NameMatcher.Prefix("Show").matches(fold("show a")))
        assertFalse(NameMatcher.Prefix("Show").matches(fold("A show")))
        assertTrue(NameMatcher.Contains("how").matches(fold("Show A")))
        assertFalse(NameMatcher.Contains("zzz").matches(fold("Show A")))
        assertEquals("show a", NameMatcher.Exact("  Show A\u00A0").folded)
    }

    @Test
    fun wordMatchesOnWordBoundariesOfTheFoldedName() {
        val word = NameMatcher.Word("ABC")
        assertTrue(word.matches(fold("abc")))
        assertTrue(word.matches(fold("z abc")))
        assertTrue(word.matches(fold("the abc-channel")))
        assertTrue(word.matches(fold("abcd ABC")))
        assertFalse(word.matches(fold("abcd")))
        assertFalse(word.matches(fold("zabc")))
        assertFalse(word.matches(fold("abc_d")))
    }

    @Test
    fun aMatcherNeedsAName() {
        assertFailsWith<IllegalArgumentException> { NameMatcher.Contains("  ") }
    }

    @Test
    fun aNullNameMatchesNoTitleAnchor() {
        val unnamed: RuleItem = movie(idOf(1))
        val names = LibraryNames(snapshot(items = listOf(unnamed)), CollectionNames(emptyMap()))
        assertEquals(emptyList(), names.titles(NameMatcher.Contains("a")))
    }

    @Test
    fun aWordMatcherStartsAndEndsWithALetterOrDigit() {
        assertTrue(NameMatcher.Word("ab-c").matches(fold("an ab-c show")))
        assertFailsWith<IllegalArgumentException> { NameMatcher.Word("-ab") }
        assertFailsWith<IllegalArgumentException> { NameMatcher.Word("ab.") }
    }

    @Test
    fun everyAnimeTableWordMatchesWholeWordsOnlyAndEveryContainsMatchesInside() {
        for (m in CurationTables.ANIME_STUDIOS) {
            when (m) {
                is NameMatcher.Word -> {
                    assertTrue(m.matches(fold("studio ${m.folded} inc")), m.folded)
                    assertFalse(m.matches(fold("x${m.folded}y")), m.folded)
                }

                is NameMatcher.Contains -> {
                    assertTrue(m.matches(fold("x ${m.folded} y")), m.folded)
                }

                else -> {
                    error("The table holds only Word and Contains")
                }
            }
        }
        assertTrue(CurationTables.ANIME_STUDIOS.any { it is NameMatcher.Word })
        assertTrue(CurationTables.ANIME_STUDIOS.any { it is NameMatcher.Contains })
    }

    @Test
    fun theCurationTablesVersionIsPinned() {
        assertEquals("curation-1", CurationTables.VERSION_NAME)
    }
}
