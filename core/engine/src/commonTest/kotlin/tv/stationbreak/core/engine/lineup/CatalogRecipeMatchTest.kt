// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.SourceMatcher
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.movie
import tv.stationbreak.core.engine.snapshot
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Catalog 7 recipes, through the real SourceMatcher (#1013). Items are invented; only the tag strings are real. */
class CatalogRecipeMatchTest {
    private fun matches(
        key: String,
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
    ): Boolean {
        val item = movie(idOf(1), genres = genres, tags = tags)
        return SourceMatcher(StarterCatalog.recipe(key)!!.sources, snapshot(listOf(item))).matches(item, null)
    }

    // One item per spelling the #994 pack added, each tagged with only that spelling.
    @Test
    fun anItemTaggedWithEachNewSpellingMatchesItsRecipe() {
        val spellings =
            mapOf(
                "punchline" to "stand-up comedy",
                "life-stories" to "based on true story",
                "end-times" to "post-apocalyptic future",
                "disaster-zone" to "natural disaster",
                "order-in-the-court" to "lawyer",
                "drive-in-double" to "b movie",
            )
        for ((key, tag) in spellings) {
            assertTrue(matches(key, tags = listOf(tag)), "$key should match tag '$tag'")
            assertFalse(matches(key, tags = listOf("unrelated")), "$key should not match an unrelated tag")
        }
    }

    // A pin of the recipe as it stands, so a later edit to Front Lines is a visible choice.
    @Test
    fun frontLinesIsPinnedToItsGenreAndTags() {
        val sources = StarterCatalog.recipe("front-lines")!!.sources
        assertEquals(
            listOf(Source.Genre("War"), Source.Tag("war"), Source.Tag("world war"), Source.Tag("military")),
            sources,
        )
        assertTrue(matches("front-lines", genres = listOf("War")))
        assertFalse(matches("front-lines", genres = listOf("War & Politics")))
    }
}
