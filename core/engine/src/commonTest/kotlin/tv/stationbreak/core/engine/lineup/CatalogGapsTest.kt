// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.movie
import tv.stationbreak.core.engine.snapshot
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Catalog 5 (#786, docs/design/lineup-seats-and-gaps.md section 4): homes for adult animation, for a sitcom with one
 * extra genre, and a lower qualifying bar for the catalog's single lane of a niche theme.
 */
class CatalogGapsTest {
    private fun recipe(key: String): Recipe = checkNotNull(StarterCatalog.recipe(key)) { "no recipe $key" }

    private val laughTrack = recipe("laugh-track")
    private val comedyCounty = recipe("satire-desk")
    private val cartoons = recipe("cartoons-after-dark")

    // The recipes given the lower bar, by name. Membership is a judgement call, recorded in the catalog: each is the
    // starter a plain title of a niche genre or form (Horror, Western, War, Soap, Talk, Reality, grown-up Music,
    // Anime, adult animation) most directly belongs on. No rule derives the set, and other recipes would fit a
    // similar description; changing it is a catalog change (a new version), so this list is pinned.
    private val nicheKeys =
        setOf(
            "midnight-projector",
            "dusty-trails",
            "front-lines",
            "matinee-tears",
            "couch-talk",
            "glass-house",
            "backstage-pass",
            "tokyo-after-school",
            "cartoons-after-dark",
        )

    @Test
    fun cartoonsAfterDarkTakesAnimationButNotFamily() {
        assertTrue(Source.Genre("Animation") in cartoons.sources)
        assertEquals(listOf("Family"), cartoons.filters?.notGenres)
        assertEquals(listOf("Animation"), cartoons.filters?.genres)
        // The tags and anchors it had stay.
        assertTrue(cartoons.sources.containsAll(listOf(Source.Tag("adult animation"), Source.Tag("animated sitcom"))))
        assertTrue(checkNotNull(cartoons.theme).anchors.isNotEmpty())
    }

    @Test
    fun cartoonsAfterDarkPoolHoldsAnAdultCartoonAndNotAFamilyOne() {
        val adult = movie(idOf(1), rating = "TV-MA", genres = listOf("Animation", "Comedy"))
        val family = movie(idOf(2), rating = "PG", genres = listOf("Animation", "Family"))
        val plainPg = movie(idOf(3), rating = "PG", genres = listOf("Animation", "Comedy"))
        val live = movie(idOf(4), rating = "TV-MA", genres = listOf("Comedy"))
        val library = snapshot(items = listOf(adult, family, plainPg, live))
        val names = CollectionNames(emptyMap())
        val rules = RecipeResolver.resolve(cartoons, SERVER, 0, names, LibraryNames(library, names))
        val kept =
            PoolEvaluator
                .evaluate(rules, library, KidStatus.policyOf(cartoons))
                .items
                .map { it.id }
                .toSet()
        assertTrue(adult.item.id in kept, "an adult animated title with no tag and no studio match airs")
        assertFalse(family.item.id in kept, "a PG animated family film does not")
        assertTrue(plainPg.item.id in kept, "a PG animated title with no Family genre does (the hold passes PG)")
        assertFalse(live.item.id in kept)
    }

    @Test
    fun laughTrackExcludesWhatIsNotASitcomAndComedyCountysTwists() {
        val excluded = checkNotNull(laughTrack.filters?.notGenres).toSet()
        for (notASitcom in listOf("Animation", "Documentary", "Reality", "News", "Talk")) {
            assertTrue(notASitcom in excluded, notASitcom)
        }
        // Oddly Funny's twist genres stay out of Laugh Track (102), or it would take Oddly Funny's (190) ties.
        val twists = checkNotNull(comedyCounty.filters?.genres)
        assertTrue(twists.isNotEmpty())
        assertEquals(emptyList(), twists.filter { it !in excluded }, "a twist genre Laugh Track would take")
        assertTrue(Source.Genre("Comedy") in comedyCounty.sources)
    }

    @Test
    fun laughTrackTakesASitcomWithAnExtraGenreThatIsNotATwist() {
        val excluded = checkNotNull(laughTrack.filters?.notGenres).toSet()
        for (extra in listOf("Drama", "Adventure", "History", "War", "War & Politics", "Western")) {
            assertFalse(extra in excluded, extra)
        }
        for (own in listOf("Comedy", "Family", "Romance")) assertFalse(own in excluded, own)
        // It is narrower than the 24 genres it excluded before, and every genre it frees is a TMDB genre.
        assertTrue(excluded.all { it in TMDB_GENRES })
        assertTrue(excluded.size < TMDB_GENRES.size - 3)
    }

    @Test
    fun laughTrackLeavesOutNoGenreThatDoesNothing() {
        val excluded = checkNotNull(laughTrack.filters?.notGenres)
        // Every grown-up recipe excludes the kids' genre already, and "TV Movie" is no genre of an episode.
        assertFalse("Kids" in excluded)
        assertFalse("TV Movie" in excluded)
        assertEquals(excluded.size, excluded.toSet().size, "no genre twice")
    }

    @Test
    fun anAdultTaggedCartoonWithFamilyIsOffCartoonsAfterDark() {
        val genres = listOf("Animation", "Family")
        val tagged = movie(idOf(5), rating = "TV-MA", genres = genres, tags = listOf("adult animation"))
        val library = snapshot(items = listOf(tagged))
        val names = CollectionNames(emptyMap())
        val rules = RecipeResolver.resolve(cartoons, SERVER, 0, names, LibraryNames(library, names))
        val kept = PoolEvaluator.evaluate(rules, library, KidStatus.policyOf(cartoons)).items.map { it.id }
        assertFalse(tagged.item.id in kept, "the Family exclusion takes it off, though its tag matches")
    }

    @Test
    fun theNicheRecipesHaveTheLowerBarAndNoOtherRecipeDoes() {
        val niche = StarterCatalog.recipes.filter { it.qualifyingBar == QualifyingBar.Niche }
        assertEquals(nicheKeys, niche.map { it.key }.toSet())
        assertTrue(niche.none { it.kidSafe || it.optIn }, "a kid or opt-in recipe keeps the standard bar")
        val others = StarterCatalog.recipes.filter { it.key !in nicheKeys }
        assertTrue(others.all { it.qualifyingBar == QualifyingBar.Standard })
    }

    @Test
    fun theLowerBarIsSixHoursAndFourTitles() {
        assertEquals(6 * 3_600_000L, QualifyingBar.Niche.usableMs)
        assertEquals(4, QualifyingBar.Niche.titles)
        assertEquals(12 * 3_600_000L, QualifyingBar.Standard.usableMs)
        assertEquals(6, QualifyingBar.Standard.titles)
    }

    @Test
    fun aKidRecipeCannotCarryTheLowerBar() {
        val kids = recipe("little-farm")
        val failure = runCatching { kids.copy(qualifyingBar = QualifyingBar.Niche) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
