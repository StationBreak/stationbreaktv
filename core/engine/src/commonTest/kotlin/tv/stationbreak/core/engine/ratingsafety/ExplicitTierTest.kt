// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.ratingsafety

import tv.stationbreak.core.engine.GrownUpHold
import tv.stationbreak.core.engine.KidHold
import tv.stationbreak.core.engine.RatingMap
import tv.stationbreak.core.engine.RatingTier
import tv.stationbreak.core.engine.episode
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.movie
import tv.stationbreak.core.engine.series
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Explicit tier of `kid-hold-8` (#1078): every trigger alone makes a title Explicit, on a movie, a series (its
 * episodes inherit) and an episode, anime or not; the Adult signals that are not triggers stay Adult.
 */
class ExplicitTierTest {
    private val showId = idOf(0x800)

    // The ratings of #1078, as scrapers write them, with and without a country prefix.
    private val explicitRatings =
        listOf(
            "X",
            "XXX",
            "AO",
            "Adults Only",
            "NC-17",
            "NC17",
            "TV-X",
            "TV-AO",
            "R18",
            "GB-R18",
            "NZ-R18",
            "R18+",
            "AU-R18+",
            "JP-R18+",
            "R-18",
            "JP-R-18",
            "X18+",
            "AU-X18+",
            "Restricted Screening",
            "KR-Restricted Screening",
            "제한상영가",
            "KR-제한상영가",
            "18 Restricted",
            "gb-r18",
            "GB:R18",
            "rated x",
        )

    private val explicitGenres = listOf("Hentai", "Adult", "Erotica", "Erotic", "Porn", "Pornography", "Sexploitation")

    private val explicitTags =
        listOf(
            "hentai",
            "pornography",
            "porn",
            "pornographic video",
            "pornographic animation",
            "porn films",
            "porn classic",
            "softcore",
            "sexploitation",
            "sexsploitation",
            "erotic movie",
            "erotic film",
            "erotica",
            "vintage erotica",
            "x rated",
            "explicite sex",
            "adult video",
            "x-rated",
            "explicit sex",
            "adult film",
            "18 restricted",
        )

    private fun tierOf(
        entry: RuleItem,
        series: RuleSeries? = null,
    ) = RatingTier.of(entry, series)

    // A trigger on its own, in every shape: a movie, an episode of a series that carries it, an episode that carries it
    // on a clean series, anime or not. A clean title is otherwise TV-14 (teen) so only the trigger can move it.
    private fun assertExplicitEverywhere(
        what: String,
        rating: String? = null,
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
    ) {
        for (anime in listOf(false, true)) {
            val extra = if (anime) listOf("Anime") else emptyList()
            val g = genres + extra
            val film = movie(idOf(1), rating = rating ?: "TV-14", genres = g, tags = tags)
            assertEquals(RatingTier.Explicit, tierOf(film), "movie, $what, anime $anime")
            // The stripped copy: the trigger and nothing else.
            val bare = movie(idOf(1), rating = rating, genres = genres, tags = tags)
            assertEquals(RatingTier.Explicit, tierOf(bare), "stripped movie, $what")
            val show = series(showId, rating = rating ?: "TV-14", genres = g, tags = tags)
            val inherited = episode(idOf(2), showId, rating = null)
            assertEquals(RatingTier.Explicit, tierOf(inherited, show), "episode inheriting, $what, anime $anime")
            val clean = series(showId, rating = "TV-14", genres = extra)
            val own = episode(idOf(2), showId, rating = rating, genres = genres, tags = tags)
            assertEquals(RatingTier.Explicit, tierOf(own, clean), "episode's own, $what, anime $anime")
            val alone = episode(idOf(2), null, rating = rating ?: "TV-14", genres = g, tags = tags)
            assertEquals(RatingTier.Explicit, tierOf(alone), "episode with no series, $what")
        }
    }

    @Test
    fun everyExplicitRatingAloneIsExplicit() {
        for (rating in explicitRatings) assertExplicitEverywhere("rating [$rating]", rating = rating)
    }

    @Test
    fun everyExplicitGenreAloneIsExplicit() {
        for (genre in explicitGenres) assertExplicitEverywhere("genre $genre", genres = listOf(genre))
        assertExplicitEverywhere("genre folded", genres = listOf("HENTAI"))
    }

    @Test
    fun everyExplicitTagAloneIsExplicit() {
        for (tag in explicitTags) assertExplicitEverywhere("tag $tag", tags = listOf(tag))
        assertExplicitEverywhere("tag folded", tags = listOf("Softcore"))
    }

    @Test
    fun theAdultSignalsThatAreNotTriggersStayAdult() {
        val adult =
            listOf(
                movie(idOf(1), rating = "TV-MA", tags = listOf("ecchi", "nudity", "harem")),
                movie(idOf(1), rating = "TV-14", tags = listOf("ecchi")),
                movie(idOf(1), rating = "PG", tags = listOf("nudity")),
                movie(idOf(1), rating = "PG-13", tags = listOf("sex comedy")),
                movie(idOf(1), rating = "PG-13", tags = listOf("teen sex comedy")),
                movie(idOf(1), rating = "R", tags = listOf("erotic thriller")),
                movie(idOf(1), rating = "TV-MA", tags = listOf("erotic")),
                movie(idOf(1), rating = "TV-14", tags = listOf("sex")),
                movie(idOf(1), rating = "TV-14", genres = listOf("Ecchi")),
                movie(idOf(1), rating = "R"),
                movie(idOf(1), rating = "TV-MA"),
                movie(idOf(1), rating = "NR"),
                movie(idOf(1), rating = "R", tags = listOf("porn industry")),
                movie(idOf(1), rating = "R", tags = listOf("porn star")),
                movie(idOf(1), rating = "R", tags = listOf("softcore porn")),
                movie(idOf(1), rating = "R15+"),
                movie(idOf(1), rating = "18"),
            )
        for (entry in adult) assertEquals(RatingTier.Adult, tierOf(entry), entry.toString())
    }

    @Test
    fun theTwoPornKeywordsOfThePlotAreNotTriggers() {
        assertEquals(RatingTier.Teen, tierOf(movie(idOf(1), rating = "TV-14", tags = listOf("porn industry"))))
        assertEquals(RatingTier.Teen, tierOf(movie(idOf(1), rating = "PG-13", tags = listOf("porn star"))))
    }

    @Test
    fun theThreeNewAdultTagsKeepATitleOutOfTeenAndFamily() {
        for (tag in listOf("sex comedy", "teen sex comedy", "erotic thriller")) {
            for (rating in listOf("G", "PG", "PG-13", "TV-14")) {
                val film = movie(idOf(1), rating = rating, tags = listOf(tag))
                assertEquals(RatingTier.Adult, tierOf(film), "$tag $rating")
            }
        }
    }

    @Test
    fun aTitleWithNoMetadataIsAdultNotExplicit() {
        val film = movie(idOf(1))
        assertEquals(RatingTier.Adult, tierOf(film))
        assertTrue(GrownUpHold.hasNoMetadata(film, null))
        val show = series(showId)
        val ep = episode(idOf(2), showId)
        assertEquals(RatingTier.Adult, tierOf(ep, show))
        assertTrue(GrownUpHold.hasNoMetadata(ep, show))
        assertTrue(GrownUpHold.hasNoMetadata(movie(idOf(1), rating = "  "), null), "a blank rating is none")
        // Any one field is metadata: a rating (NR included), a genre or a tag, on the entry or its series.
        assertFalse(GrownUpHold.hasNoMetadata(movie(idOf(1), rating = "NR"), null))
        assertFalse(GrownUpHold.hasNoMetadata(movie(idOf(1), genres = listOf("Drama")), null))
        assertFalse(GrownUpHold.hasNoMetadata(movie(idOf(1), tags = listOf("test")), null))
        assertFalse(GrownUpHold.hasNoMetadata(ep, series(showId, rating = "TV-MA")))
        assertFalse(GrownUpHold.hasNoMetadata(ep, series(showId, genres = listOf("Drama"))))
        assertFalse(GrownUpHold.hasNoMetadata(ep, series(showId, tags = listOf("test"))))
    }

    @Test
    fun theTiersAreOrderedUpToExplicitAndTheGrownUpHoldDropsKidsAndExplicit() {
        assertEquals(
            listOf(RatingTier.Kids, RatingTier.Family, RatingTier.Teen, RatingTier.Adult, RatingTier.Explicit),
            RatingTier.entries.sorted(),
        )
        val explicit = movie(idOf(1), rating = "XXX")
        assertTrue(GrownUpHold.dropsFromGrownUp(explicit, null))
        assertFalse(KidHold.admits(explicit, null))
        assertFalse(GrownUpHold.dropsFromGrownUp(movie(idOf(1), rating = "R"), null))
        assertTrue(GrownUpHold.dropsFromGrownUp(movie(idOf(1), rating = "G", genres = listOf("Kids")), null))
    }

    @Test
    fun theRatingMapPutsXAoAndAdultsOnlyAtTheTopAndLeavesTheRestAsTheyWere() {
        for (rating in listOf("X", "AO", "Adults Only", "x", "Rated X")) {
            assertEquals(7, RatingMap.levelOf(rating), rating)
        }
        assertEquals(6, RatingMap.levelOf("NC-17"), "NC-17 keeps its level; it is Explicit by the list")
        for (rating in listOf("R18", "GB-R18", "R18+", "X18+", "NC17")) {
            assertEquals(null, RatingMap.levelOf(rating), rating)
        }
        for (rating in explicitRatings) assertTrue(RatingMap.isExplicit(rating), rating)
        for (rating in listOf(null, "", "R", "TV-MA", "18", "TV-14", "PG-13", "R15+", "MA15+", "GB-15", "NC", "17")) {
            assertFalse(RatingMap.isExplicit(rating), "[$rating]")
        }
    }

    // A combined rating is Explicit when any part is (fix round 1, MED-2).
    @Test
    fun aCombinedRatingIsExplicitWhenAnyPartIs() {
        for (rating in listOf("R/NC-17", "GB-R18/AU-X18+", "TV-MA / XXX", "PG-13/R18+")) {
            assertTrue(RatingMap.isExplicit(rating), rating)
            assertEquals(RatingTier.Explicit, tierOf(movie(idOf(1), rating = rating)), rating)
        }
        for (rating in listOf("R/TV-MA", "PG/PG-13", "TV-14/TV-MA", "gb/15")) {
            assertFalse(RatingMap.isExplicit(rating), rating)
        }
    }
}
