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
        assertTrue(NameMatcher.Word("fx").matches(fold("FX Movies")))
        assertTrue(NameMatcher.Word("FX").matches(fold("The FX-Channel")))
        assertFalse(NameMatcher.Word("FX").matches(fold("Foxfx")))
        assertFalse(NameMatcher.Word("FX").matches(fold("FXX")))
        assertTrue(NameMatcher.Word("FX").matches(fold("Foxfx FX")))
        assertEquals("show a", NameMatcher.Exact("  Show A ").folded)
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
        assertFailsWith<IllegalArgumentException> { NameMatcher.Word("-fx") }
        assertFailsWith<IllegalArgumentException> { NameMatcher.Word("fx.") }
        assertTrue(NameMatcher.Word("AT-X").matches(fold("AT-X Channel")))
    }

    private fun animeMatch(studio: String) = CurationTables.ANIME_STUDIOS.any { it.matches(fold(studio)) }

    @Test
    fun shortAnimeTokensMatchWholeWordsOnly() {
        assertFalse(animeMatch("Holmes Studio"))
        assertFalse(animeMatch("Shaftesbury"))
        assertTrue(animeMatch("Studio Bones Inc."))
        assertTrue(animeMatch("Shaft"))
        assertTrue(animeMatch("Production I.G"))
    }

    @Test
    fun theCurationTablesVersionIsPinned() {
        assertEquals("curation-1", CurationTables.VERSION_NAME)
    }
}
