// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec 18, "The kid hold": the made-for-kids gate and the kid rating table. Invented names only. */
class KidHoldTest {
    private val showId = idOf(0x500)

    private fun film(
        rating: String? = null,
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        studios: List<String> = emptyList(),
    ): RuleItem = movie(idOf(1), rating = rating, genres = genres, tags = tags).copy(studios = studios)

    private fun episodeOf(
        rating: String? = null,
        seriesId: CanonicalId? = showId,
        genres: List<String> = emptyList(),
        studios: List<String> = emptyList(),
    ): RuleItem = episode(idOf(2), seriesId, rating = rating, genres = genres).copy(studios = studios)

    private fun show(
        rating: String? = null,
        genres: List<String> = emptyList(),
        studios: List<String> = emptyList(),
    ): RuleSeries = series(showId, rating = rating, genres = genres).copy(studios = studios)

    // --- kidRank ---

    @Test
    fun kidRankPutsEachServerSpellingOnItsKidTier() {
        for (r in listOf("TV-Y", "TV-G", "G")) assertEquals(0, KidHold.kidRank(r), r)
        for (r in listOf("TV-Y7", "TV-Y7-FV")) assertEquals(1, KidHold.kidRank(r), r)
        for (r in listOf("PG", " pg ")) assertEquals(2, KidHold.kidRank(r), r)
        for (r in listOf("TV-PG", "tv-pg", "TV-PG-V")) assertEquals(3, KidHold.kidRank(r), r)
        for (r in listOf("PG-13", "TV-14-DLSV", "R", "TV-MA", "NC-17", "TV-X")) assertEquals(4, KidHold.kidRank(r), r)
    }

    @Test
    fun kidRankReadsAnythingElseAsUnrated() {
        for (r in listOf("Approved", "NR", "Unrated", "", "12A")) assertNull(KidHold.kidRank(r), r)
        assertNull(KidHold.kidRank(null))
    }

    // --- passesKidCeiling ---

    @Test
    fun aMovieMustHaveAKidTierAtOrBelowTheCeiling() {
        assertTrue(KidHold.passesKidCeiling(film("PG"), null, "PG"))
        assertTrue(KidHold.passesKidCeiling(film("G"), null, "PG"))
        assertTrue(KidHold.passesKidCeiling(film("TV-Y7"), null, "PG"))
        assertFalse(KidHold.passesKidCeiling(film("TV-PG"), null, "PG"))
        assertFalse(KidHold.passesKidCeiling(film("Approved"), null, "PG"))
        assertFalse(KidHold.passesKidCeiling(film(null), null, "PG"))
        assertFalse(KidHold.passesKidCeiling(film(""), null, "PG"))
    }

    @Test
    fun anEpisodeWithNoOwnRatingTakesItsKnownSeries() {
        assertTrue(KidHold.passesKidCeiling(episodeOf(""), show("TV-Y7"), "PG"))
        assertTrue(KidHold.passesKidCeiling(episodeOf(null), show("TV-Y7"), "PG"))
        assertFalse(KidHold.passesKidCeiling(episodeOf(""), show("TV-PG"), "PG"))
    }

    @Test
    fun aKnownSeriesWithARatingMustPassWhateverTheEpisodeSays() {
        assertFalse(KidHold.passesKidCeiling(episodeOf("TV-Y"), show("TV-PG"), "PG"))
        assertFalse(KidHold.passesKidCeiling(episodeOf("TV-Y"), show("NR"), "PG"))
        assertTrue(KidHold.passesKidCeiling(episodeOf("TV-Y"), show(""), "PG"))
        assertTrue(KidHold.passesKidCeiling(episodeOf("TV-Y"), show(null), "PG"))
        assertFalse(KidHold.passesKidCeiling(episodeOf(""), show(null), "PG"))
    }

    @Test
    fun anEpisodeWhoseSeriesIsMissingFromTheSnapshotIsHeldOut() {
        assertFalse(KidHold.passesKidCeiling(episodeOf("G"), null, "PG"))
    }

    @Test
    fun anEpisodeWithNoSeriesIdIsCheckedOnItsOwnRating() {
        assertTrue(KidHold.passesKidCeiling(episodeOf("G", seriesId = null), null, "PG"))
        assertFalse(KidHold.passesKidCeiling(episodeOf("TV-PG", seriesId = null), null, "PG"))
        assertFalse(KidHold.passesKidCeiling(episodeOf(null, seriesId = null), null, "PG"))
    }

    @Test
    fun theCeilingIsCappedAtPgAndReadOnTheKidTable() {
        for (cap in listOf("TV-PG", "PG-13", null)) {
            assertTrue(KidHold.passesKidCeiling(film("PG"), null, cap), "PG under $cap")
            assertFalse(KidHold.passesKidCeiling(film("TV-PG"), null, cap), "TV-PG under $cap")
        }
        assertTrue(KidHold.passesKidCeiling(film("G"), null, "G"))
        assertFalse(KidHold.passesKidCeiling(film("TV-Y7"), null, "G"))
        assertTrue(KidHold.passesKidCeiling(film("G"), null, "Approved"))
        assertFalse(KidHold.passesKidCeiling(film("TV-Y7"), null, "Approved"))
    }

    // --- isMadeForKids ---

    @Test
    fun aKidsGenreOnTheItemMakesItMadeForKids() {
        for (g in listOf("children", "Kids", "Preschool", "Children’s", "Pre-school")) {
            assertTrue(KidHold.isMadeForKids(film(genres = listOf("Comedy", g)), null), g)
        }
    }

    @Test
    fun familyIsNotAKidsGenreAndTagsNeverCount() {
        assertFalse(KidHold.isMadeForKids(film(genres = listOf("Family")), null))
        assertFalse(KidHold.isMadeForKids(film(tags = listOf("kids")), null))
        assertFalse(KidHold.isMadeForKids(film(), null))
    }

    @Test
    fun aKidStudioInAnySpellingCaseMakesItMadeForKids() {
        assertTrue(KidHold.isMadeForKids(film(studios = listOf("Other Co", "NICK JR.")), null))
        assertTrue(KidHold.isMadeForKids(film(studios = listOf("cbeebies")), null))
        assertFalse(KidHold.isMadeForKids(film(studios = listOf("Invented Pictures")), null))
    }

    @Test
    fun anEpisodeTakesAKnownSeriesGenreOrStudio() {
        assertTrue(KidHold.isMadeForKids(episodeOf(), show(genres = listOf("Kids"))))
        assertTrue(KidHold.isMadeForKids(episodeOf(), show(studios = listOf("Noggin"))))
        assertFalse(KidHold.isMadeForKids(episodeOf(), show(genres = listOf("Drama"))))
    }

    @Test
    fun anEpisodeWhoseSeriesIsMissingMatchesOnItsOwnNamesOnly() {
        assertFalse(KidHold.isMadeForKids(episodeOf(), null))
        assertTrue(KidHold.isMadeForKids(episodeOf(studios = listOf("Noggin")), null))
        assertTrue(KidHold.isMadeForKids(episodeOf(genres = listOf("Kids")), null))
    }

    // --- kid hold 2: children's ratings and animation studios ---

    @Test
    fun aChildrensRatingMakesItMadeForKidsWithOnlyAThemeGenre() {
        assertTrue(KidHold.isMadeForKids(film("TV-Y", genres = listOf("Adventure")), null))
        assertTrue(KidHold.isMadeForKids(film("TV-Y7", genres = listOf("Adventure")), null))
        assertTrue(KidHold.isMadeForKids(film("tv-y7-fv"), null))
        assertTrue(KidHold.isMadeForKids(film(" TV-Y7-FV "), null))
    }

    @Test
    fun aGeneralAudienceRatingAloneIsNotMadeForKids() {
        assertFalse(KidHold.isMadeForKids(film("TV-G"), null))
        assertFalse(KidHold.isMadeForKids(film("G"), null))
        assertFalse(KidHold.isMadeForKids(film("PG"), null))
    }

    @Test
    fun anEpisodeTakesAChildrensRatingFromItsKnownSeries() {
        assertTrue(KidHold.isMadeForKids(episodeOf(""), show("TV-Y7")))
        assertTrue(KidHold.isMadeForKids(episodeOf(null), show("TV-Y")))
        assertFalse(KidHold.isMadeForKids(episodeOf(""), show("TV-G")))
    }

    @Test
    fun aMissingSeriesContributesNoRating() {
        assertFalse(KidHold.isMadeForKids(episodeOf(""), null))
        assertTrue(KidHold.isMadeForKids(episodeOf("TV-Y"), null))
    }

    @Test
    fun anAnimationStudioMakesItMadeForKidsExceptTheOneLeftOff() {
        assertTrue(KidHold.isMadeForKids(film(studios = listOf("Pixar")), null))
        assertTrue(KidHold.isMadeForKids(film(studios = listOf("dreamworks animation")), null))
        assertFalse(KidHold.isMadeForKids(film(studios = listOf("Studio Ghibli")), null))
        assertFalse(KidHold.isMadeForKids(film(studios = listOf("DreamWorks Pictures")), null))
    }

    // --- the frozen manifest ---

    // A change to either list is a new version name: the hold's behaviour is versioned by it.
    @Test
    fun theManifestIsFrozenUnderItsVersionName() {
        assertEquals("kid-hold-2", KidHold.VERSION_NAME)
        assertEquals(
            listOf("Children", "Children's", "Children’s", "Childrens", "Kids", "Kid", "Preschool", "Pre-school"),
            KidHold.KIDS_GENRES,
        )
    }

    @Test
    fun theStudioListIsFrozenUnderItsVersionName() {
        assertEquals(
            listOf(
                "Nickelodeon",
                "Nickelodeon Animation Studio",
                "Nickelodeon Productions",
                "Nickelodeon Movies",
                "Nick Jr.",
                "Nick Jr",
                "Nick Jr. Productions",
                "Nicktoons",
                "Noggin",
                "Disney Junior",
                "Disney Jr.",
                "Playhouse Disney",
                "Disney Channel",
                "Disney XD",
                "Toon Disney",
                "Cartoon Network",
                "Cartoon Network Studios",
                "Cartoonito",
                "Boomerang",
                "PBS Kids",
                "PBS KIDS",
                "Sesame Workshop",
                "Children's Television Workshop",
                "CBeebies",
                "CBBC",
                "BBC Children's",
                "Treehouse TV",
                "TVOKids",
                "ABC Kids",
                "Universal Kids",
                "Sprout",
                "BabyTV",
                "Baby TV",
                "Discovery Kids",
                "Qubo",
                "Kids' WB",
                "4Kids Entertainment",
                "HiT Entertainment",
            ),
            KidHold.KID_STUDIOS.take(CHANNEL_AND_LABEL_STUDIOS),
        )
    }

    @Test
    fun theAnimationStudiosFollowTheChannelAndLabelStudios() {
        assertEquals(
            listOf(
                "Walt Disney Animation Studios",
                "Walt Disney Feature Animation",
                "Disney Television Animation",
                "Pixar",
                "Pixar Animation Studios",
                "DreamWorks Animation",
                "Illumination",
                "Illumination Entertainment",
                "Blue Sky Studios",
                "Sony Pictures Animation",
                "Aardman",
                "Aardman Animations",
                "Laika",
                "Hanna-Barbera",
                "Hanna-Barbera Productions",
                "Jim Henson Company",
                "The Jim Henson Company",
                "Rankin/Bass",
                "Rankin/Bass Productions",
                "Nelvana",
                "WildBrain",
                "DHX Media",
            ),
            KidHold.KID_STUDIOS.drop(CHANNEL_AND_LABEL_STUDIOS),
        )
    }

    private companion object {
        /** The studios `kid-hold-1` already had; the animation studios of `kid-hold-2` come after them. */
        const val CHANNEL_AND_LABEL_STUDIOS = 38
    }
}
