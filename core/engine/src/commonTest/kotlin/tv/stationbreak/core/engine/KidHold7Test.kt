// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.studioKey2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The moves up of `kid-hold-7` (ADR-0029): #988, #989, #990 and #1001. Each only ever moves a title up. */
class KidHold7Test {
    private val showId = idOf(0x700)

    private fun tier(
        rating: String?,
        tags: List<String> = emptyList(),
        genres: List<String> = emptyList(),
        studios: List<String> = emptyList(),
    ): RatingTier {
        val film = movie(idOf(1), rating = rating, genres = genres, tags = tags, studios = studios)
        return RatingTier.of(film, null)
    }

    // --- #988: violence-heavy tags keep a PG or GP title out of family ---

    @Test
    fun aViolenceHeavyTagMovesAPgOrGpFilmToTeen() {
        for (tag in listOf("mass murder", "decapitation", "child-death", "Mass Murder", "CHILD-DEATH")) {
            for (rating in listOf("PG", "GP", "TV-PG")) {
                assertEquals(RatingTier.Teen, tier(rating, tags = listOf(tag)), "$rating $tag")
            }
            assertEquals(RatingTier.Family, tier("PG"), "no tag")
        }
    }

    @Test
    fun plainMurderAndKillerDoNotBump() {
        for (tag in listOf("murder", "killer", "serial killer", "mass", "death", "child")) {
            assertEquals(RatingTier.Family, tier("PG", tags = listOf(tag)), tag)
            assertEquals(RatingTier.Family, tier("GP", tags = listOf(tag)), tag)
        }
    }

    @Test
    fun theViolenceTagsNeverSendATitleToAdultOnTheirOwnButAnExplicitTagStillDoes() {
        assertEquals(RatingTier.Teen, tier("PG-13", tags = listOf("mass murder")))
        assertEquals(RatingTier.Teen, tier("PG", tags = listOf("child-death", "murder")))
        assertEquals(RatingTier.Adult, tier("PG", tags = listOf("mass murder", "nudity")))
    }

    @Test
    fun aSeriesTagMovesItsEpisodes() {
        val show = series(showId, rating = "TV-PG", tags = listOf("child-death"))
        val ep = episode(idOf(2), showId)
        assertEquals(RatingTier.Teen, RatingTier.of(ep, show))
        assertEquals(RatingTier.Family, RatingTier.of(ep, series(showId, rating = "TV-PG")))
    }

    // The kids rule is unchanged: a title that is kids on a kid rating and evidence stays kids.
    @Test
    fun aKidsTitleWithAFamilyVetoStaysKids() {
        val kids = listOf("Kids")
        assertEquals(RatingTier.Kids, tier("G", tags = listOf("child-death"), genres = kids))
        assertEquals(RatingTier.Kids, tier("TV-Y7", genres = listOf("Kids", "Horror")))
        assertEquals(RatingTier.Teen, tier("PG", tags = listOf("child-death"), genres = kids))
    }

    // --- #989: TV Tokyo is not kid evidence ---

    @Test
    fun tvTokyoIsNotAKidStudioInAnySpelling() {
        val keys = KidHold.KID_STUDIOS.map(::studioKey2).toSet()
        for (name in listOf("TV Tokyo", "TV Tokyo Corporation", "TVTokyo", "TV-Tokyo", "tv tokyo", "TV TOKYO")) {
            assertFalse(studioKey2(name) in keys, name)
            assertFalse(
                KidHold.isMadeForKids(movie(idOf(1), rating = "TV-Y7", studios = listOf(name)), null),
                name,
            )
        }
        assertFalse(KidHold.KID_STUDIOS.any { it.contains("Tokyo", ignoreCase = true) })
        assertEquals(RatingTier.Family, tier("TV-Y7", studios = listOf("TV Tokyo")))
    }

    // --- #990: the bump-to-family list ---

    private val rens = mapOf("tvdb" to "76088")

    @Test
    fun aListedKidsSeriesIsFamily() {
        val show = series(showId, rating = "TV-Y7", genres = listOf("Animation", "Family"), providerIds = rens)
        val ep = episode(idOf(2), showId)
        assertEquals(RatingTier.Family, RatingTier.of(ep, show))
        assertEquals(RatingTier.Kids, RatingTier.of(ep, show.copy(providerIds = emptyMap())))
        val listedIds =
            listOf(mapOf("tmdb" to "504"), mapOf("imdb" to "tt0101178"), mapOf("imdb" to "tt1", "tvdb" to "76088"))
        for (ids in listedIds) {
            assertEquals(RatingTier.Family, RatingTier.of(ep, show.copy(providerIds = ids)), ids.toString())
        }
        assertFalse(KidHold.admits(ep, show))
        assertTrue(GrownUpHold.dropsFromGrownUp(ep, show.copy(providerIds = emptyMap())))
        assertFalse(GrownUpHold.dropsFromGrownUp(ep, show))
    }

    @Test
    fun theListMatchesOnIdsNeverOnTheTitle() {
        val show =
            RuleSeries(showId, "TV-Y7", listOf("Animation", "Family"), emptyList(), name = "The Ren & Stimpy Show")
        assertEquals(RatingTier.Kids, RatingTier.of(episode(idOf(2), showId), show))
        val notListed =
            listOf(mapOf("tvdb" to "504"), mapOf("TVDB" to "76088"), mapOf("tvdb" to " 76088"), mapOf("x" to "76088"))
        for (ids in notListed) {
            assertEquals(RatingTier.Kids, RatingTier.of(episode(idOf(2), showId), show.copy(providerIds = ids)), "$ids")
        }
    }

    // The list moves kids to family and nothing else: Adult Party Cartoon is TV-MA, so a mis-scrape onto the 1991 ids
    // leaves it adult. A listed id never lifts a title either: TV-14 stays teen, an unrated one stays adult.
    @Test
    fun theListCanNeverMoveATitleDown() {
        val ep = episode(idOf(2), showId)
        val adultParty = series(showId, rating = "TV-MA", genres = listOf("Animation", "Comedy"), providerIds = rens)
        assertEquals(RatingTier.Adult, RatingTier.of(ep, adultParty))
        assertEquals(RatingTier.Adult, RatingTier.of(ep.copy(officialRating = "TV-Y7"), adultParty))
        assertEquals(RatingTier.Teen, RatingTier.of(ep, adultParty.copy(officialRating = "TV-14")))
        assertEquals(RatingTier.Adult, RatingTier.of(ep, adultParty.copy(officialRating = null)))
        val explicit = adultParty.copy(officialRating = "TV-Y7", tags = listOf("nudity"))
        assertEquals(RatingTier.Adult, RatingTier.of(ep, explicit))
        // Adult Party Cartoon's own ids (tvdb 73287, imdb tt0371475) are not on the list.
        val real = adultParty.copy(providerIds = mapOf("tvdb" to "73287", "imdb" to "tt0371475"))
        assertEquals(RatingTier.Adult, RatingTier.of(ep, real))
        assertFalse(KidBumpList.lists(real))
    }

    @Test
    fun everyEntryHasAReasonAndIdsAndNoUnknownKeys() {
        assertTrue(KidBumpList.ENTRIES.isNotEmpty())
        for (entry in KidBumpList.ENTRIES) {
            assertTrue(entry.reason.isNotBlank(), "an entry has no reason")
            assertTrue(entry.ids.isNotEmpty(), "an entry has no id: ${entry.reason}")
            for ((key, value) in entry.ids) {
                assertTrue(key in setOf("imdb", "tmdb", "tvdb"), key)
                assertTrue(value.isNotBlank() && value == value.trim(), "$key id")
            }
        }
    }

    @Test
    fun theFirstEntryIsTheRenAndStimpyShow() {
        assertEquals(1, KidBumpList.ENTRIES.size)
        assertEquals(mapOf("tvdb" to "76088", "tmdb" to "504", "imdb" to "tt0101178"), KidBumpList.ENTRIES.single().ids)
    }

    @Test
    fun anUnknownSeriesOrAMovieIsNeverListed() {
        assertFalse(KidBumpList.lists(null))
        assertFalse(KidBumpList.lists(series(showId)))
        val ep = episode(idOf(2), showId, rating = "TV-Y7", genres = listOf("Kids"))
        assertEquals(RatingTier.Adult, RatingTier.of(ep, null))
    }

    // --- #1001: the Horror genre keeps a PG title out of family ---

    @Test
    fun horrorMovesAFamilyTitleToTeen() {
        assertEquals(RatingTier.Teen, tier("PG", genres = listOf("Horror")))
        assertEquals(RatingTier.Teen, tier("GP", genres = listOf("Horror", "Mystery")))
        assertEquals(RatingTier.Teen, tier("TV-PG", genres = listOf("HORROR")))
        assertEquals(RatingTier.Teen, tier("TV-Y", genres = listOf("Horror")))
        val ep = episode(idOf(2), showId)
        assertEquals(RatingTier.Teen, RatingTier.of(ep, series(showId, rating = "TV-PG", genres = listOf("Horror"))))
    }

    @Test
    fun thrillerMysteryAndAPlainAdventureStayFamily() {
        assertEquals(RatingTier.Family, tier("PG", genres = listOf("Thriller")))
        assertEquals(RatingTier.Family, tier("PG", genres = listOf("Mystery", "Thriller")))
        assertEquals(RatingTier.Family, tier("PG", genres = listOf("Adventure")))
        assertEquals(RatingTier.Family, tier("PG", genres = listOf("Horror comedy", "Scary")))
    }

    @Test
    fun horrorNeverLowersATitleOrPassesAnExplicitOne() {
        assertEquals(RatingTier.Teen, tier("PG-13", genres = listOf("Horror")))
        assertEquals(RatingTier.Adult, tier("R", genres = listOf("Horror")))
        assertEquals(RatingTier.Adult, tier("PG", genres = listOf("Horror"), tags = listOf("nudity")))
        assertEquals(RatingTier.Adult, tier(null, genres = listOf("Horror")))
    }

    // Horror was never an adult genre for kids: a kid-rated title with kids evidence stays kids (#1001).
    @Test
    fun aKidsHorrorTitleStaysKidsAndABumpedHorrorOneIsTeen() {
        assertEquals(RatingTier.Kids, tier("G", genres = listOf("Kids", "Horror")))
        val show = series(showId, rating = "TV-Y7", genres = listOf("Kids", "Horror"), providerIds = rens)
        assertEquals(RatingTier.Teen, RatingTier.of(episode(idOf(2), showId), show))
    }
}
