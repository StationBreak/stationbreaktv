// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Studio matching by format version (docs/adr/0026-studio-fold-2.md, Where each fold is used): a file that says 3
 * matches `studio` sources, `studio` exclusions and the `studios` filter under studio fold 1, one that says 4 under
 * studio fold 2, with the empty-key rule for exclusions.
 */
class StudioFold2MatchingTest {
    // Look-alikes of "Walt Disney" that fold 1 does not join and fold 2 does.
    private val lookAlikes =
        listOf(
            "Walt\u00A0Disney",
            "Walt  Disney",
            "Walt\u3000Disney",
            "Walt\u3164Disney",
            "Walt\u000BDisney",
            "WaltDisney",
            "Walt Dis\u200Dney",
            "Walt Dis\uDB40\uDC02ney",
        )

    private val items = lookAlikes.mapIndexed { i, name -> movie(idOf(i + 1), studios = listOf(name)) }
    private val plain = movie(idOf(0x50), studios = listOf("Walt Disney"))
    private val other = movie(idOf(0x51), studios = listOf("Pixar"))
    private val all = items + plain + other
    private val snap = snapshot(items = all)

    private fun pool(rules: ChannelRules): Set<Int> =
        PoolEvaluator
            .evaluate(rules, snap)
            .items
            .map { item -> all.indexOfFirst { it.item.id == item.id } }
            .toSet()

    private val lookAlikeIndices = lookAlikes.indices.toSet()
    private val plainIndex = lookAlikes.size
    private val otherIndex = lookAlikes.size + 1

    @Test
    fun aFormat3StudioSourceMatchesUnderFold1() {
        val rules = rules(listOf(Source.Studio("Walt Disney")), formatVersion = 3)
        assertEquals(setOf(plainIndex), pool(rules))
    }

    @Test
    fun aFormat4StudioSourceMatchesTheLookAlikes() {
        val rules = rules(listOf(Source.Studio("Walt Disney")), formatVersion = 4)
        assertEquals(lookAlikeIndices + plainIndex, pool(rules))
    }

    // Version 5 (the spread day end) keeps studio fold 2: the matcher picks fold 2 for "4 or more".
    @Test
    fun aV4StudioFileStillUsesFold2AtV5() {
        val library = listOf<Source>(Source.Library(LIB))
        val studio = listOf<Source>(Source.Studio("Walt Disney"))
        val filters = Filters(null, null, null, null, null, null, studios = listOf("walt disney"))
        assertEquals(lookAlikeIndices + plainIndex, pool(rules(studio, formatVersion = 5)))
        assertEquals(setOf(otherIndex), pool(rules(library, studio, formatVersion = 5)))
        assertEquals(lookAlikeIndices + plainIndex, pool(rules(library, formatVersion = 5, filters = filters)))
    }

    @Test
    fun aFormat4StudioExclusionRemovesTheLookAlikesAndAFormat3OneDoesNot() {
        val library = listOf<Source>(Source.Library(LIB))
        val exclusion = listOf<Source>(Source.Studio("Walt Disney"))
        assertEquals(setOf(otherIndex), pool(rules(library, exclusion, formatVersion = 4)))
        assertEquals(lookAlikeIndices + otherIndex, pool(rules(library, exclusion, formatVersion = 3)))
    }

    @Test
    fun aFormat4StudiosFilterMatchesTheLookAlikes() {
        val filters = Filters(null, null, null, null, null, null, studios = listOf("walt disney"))
        val library = listOf<Source>(Source.Library(LIB))
        assertEquals(lookAlikeIndices + plainIndex, pool(rules(library, formatVersion = 4, filters = filters)))
        assertEquals(setOf(plainIndex), pool(rules(library, formatVersion = 3, filters = filters)))
    }

    // ADR-0026, The pass: under format 4 an exclusion whose key is empty excludes every item with a studio whose key
    // is empty (its own or its series'); a source or a `studios` filter whose key is empty matches nothing.
    @Test
    fun theEmptyKeyRule() {
        val blank = movie(idOf(1), studios = listOf("\u2800\u3164"))
        val blankAndNamed = movie(idOf(2), studios = listOf("Pixar", "\u3164"))
        val named = movie(idOf(3), studios = listOf("Pixar"))
        val none = movie(idOf(4))
        val show = idOf(0x30)
        val blankShow = series(show, studios = listOf("\u115F"))
        val episodeOfBlank = episode(idOf(5), show)
        val episodeMissing = episode(idOf(6), idOf(0x99))
        val list = listOf(blank, blankAndNamed, named, none, episodeOfBlank, episodeMissing)
        val s = snapshot(items = list, series = listOf(blankShow))

        fun ids(rules: ChannelRules) =
            PoolEvaluator
                .evaluate(rules, s)
                .items
                .map { it.id }
                .toSet()
        val library = listOf<Source>(Source.Library(LIB))
        val emptyName = listOf<Source>(Source.Studio("\u3164"))
        assertEquals(
            setOf(named.item.id, none.item.id, episodeMissing.item.id),
            ids(rules(library, emptyName, formatVersion = 4)),
        )
        // Under fold 1, U+3164 is kept: it matches only a studio named U+3164 exactly.
        assertEquals(
            list.map { it.item.id }.toSet() - blankAndNamed.item.id,
            ids(rules(library, emptyName, formatVersion = 3)),
        )
        assertEquals(emptySet(), ids(rules(emptyName, formatVersion = 4)))
        val filters = Filters(null, null, null, null, null, null, studios = listOf("\u3164"))
        assertEquals(emptySet(), ids(rules(library, formatVersion = 4, filters = filters)))
    }

    @Test
    fun aStudioIsTalliedOncePerItemUnderFold2() {
        val matcher =
            SourceMatcher(
                listOf(Source.Studio("Walt Disney"), Source.Studio("walt\u3000disney"), Source.Studio("Pixar")),
                snapshot(),
                studioFold2 = true,
            )
        val hits = IntArray(3)
        assertEquals(true, matcher.tally(movie(idOf(1), studios = listOf("Walt Disney", "WALT DISNEY")), null, hits))
        assertEquals(listOf(1, 1, 0), hits.toList())
    }
}
