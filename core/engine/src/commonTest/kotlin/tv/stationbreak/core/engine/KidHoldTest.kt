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
        assertFalse(KidHold.isMadeForKids(film(studios = listOf("Nelvana")), null))
        assertFalse(KidHold.isMadeForKids(film(studios = listOf("DreamWorks Pictures")), null))
    }

    // Names cached before the sync cleaned them may still hold stray characters; the hold reads the cleaned text.
    // Code points are built from numbers so the source holds no invisible character.
    private fun chars(vararg codes: Int) = codes.joinToString("") { Char(it).toString() }

    @Test
    fun aStudioWithEmbeddedControlOrFormatCharactersStillMatchesItsListedStudio() {
        val override = Char(0x202E)
        val zeroWidth = Char(0x200B)
        assertTrue(KidHold.isMadeForKids(film(studios = listOf("Pix${override}ar Animation Studios")), null))
        assertTrue(KidHold.isMadeForKids(film(studios = listOf("Nick${zeroWidth}elodeon")), null))
        assertTrue(KidHold.isMadeForKids(film(studios = listOf(chars(9) + "Pixar" + chars(0x2029))), null))
        assertTrue(KidHold.isMadeForKids(episodeOf(), show(studios = listOf("No${override}ggin"))))
    }

    @Test
    fun aTabBetweenWordsIsASpaceNotAFusion() {
        assertTrue(KidHold.isMadeForKids(film(studios = listOf("Cartoon" + chars(9) + "Network Studios")), null))
        assertFalse(KidHold.isMadeForKids(film(studios = listOf("Cartoon" + chars(0x200B) + "NetworkStudios")), null))
    }

    @Test
    fun aStudioNameOfOnlyFormatCharactersMatchesNothing() {
        assertFalse(KidHold.isMadeForKids(film(studios = listOf(chars(0x202E, 0x200B, 0x2066, 0xFEFF))), null))
        assertFalse(KidHold.isMadeForKids(film(studios = listOf(chars(0x200B), chars(9, 10))), null))
    }

    // --- kid-hold-3: animated + Family + a rating that is G, TV-G or unrated ---

    private val animatedFamily = listOf("Animation", "Family")

    @Test
    fun anAnimatedFamilyGFilmIsMadeForKids() {
        assertTrue(KidHold.isMadeForKids(film("G", genres = animatedFamily), null))
        assertTrue(KidHold.isMadeForKids(film("TV-G", genres = listOf("animation", "FAMILY", "Comedy")), null))
        assertTrue(KidHold.isMadeForKids(film(" g ", genres = animatedFamily), null))
        assertTrue(KidHold.isAnimatedFamily(film("G", genres = animatedFamily), null))
    }

    @Test
    fun anAnimatedFamilyTvGEpisodeTakesItsSeriesGenres() {
        val ep = episodeOf("TV-G")
        assertTrue(KidHold.isMadeForKids(ep, show(genres = animatedFamily)))
        // One genre on the episode, the other on the series.
        val half = episodeOf("TV-G", genres = listOf("Animation"))
        assertTrue(KidHold.isMadeForKids(half, show(genres = listOf("Family"))))
        // A missing series contributes nothing.
        assertFalse(KidHold.isMadeForKids(ep, null))
        // The effective rating is the series' when the episode has none.
        assertTrue(KidHold.isMadeForKids(episodeOf(null), show("TV-G", genres = animatedFamily)))
    }

    @Test
    fun anAnimatedFamilyEpisodeInAGrownUpRatedSeriesIsNot() {
        assertFalse(KidHold.isMadeForKids(episodeOf("TV-G"), show("TV-14", genres = animatedFamily)))
        assertFalse(KidHold.isMadeForKids(episodeOf("TV-14"), show("TV-G", genres = animatedFamily)))
        assertFalse(KidHold.isMadeForKids(episodeOf(null), show("TV-PG", genres = animatedFamily)))
    }

    @Test
    fun anUnratedAnimatedFamilyFilmIsMadeForKidsButFailsEveryCeiling() {
        for (r in listOf(null, "", "  ", "NR", "not rated", "Approved", "Unrated", " UNRATED ")) {
            val f = film(r, genres = animatedFamily)
            assertTrue(KidHold.isMadeForKids(f, null), r.toString())
            for (c in KidHold.CEILING_CHOICES + listOf(null)) {
                assertFalse(KidHold.passesKidCeiling(f, null, c), "$r $c")
            }
        }
    }

    @Test
    fun anAnimatedFamilyPg13FilmIsNot() {
        for (r in listOf("PG", "PG-13", "TV-PG", "TV-14", "R", "TV-MA", "NC-17", "12A", "X")) {
            assertFalse(KidHold.isMadeForKids(film(r, genres = animatedFamily), null), r)
        }
    }

    @Test
    fun aLiveActionFamilyGFilmIsNot() {
        assertFalse(KidHold.isMadeForKids(film("G", genres = listOf("Family", "Comedy")), null))
        assertFalse(KidHold.isMadeForKids(film("G", genres = listOf("Family"), tags = listOf("animation")), null))
    }

    @Test
    fun animatedWithoutFamilyIsNot() {
        assertFalse(KidHold.isMadeForKids(film("G", genres = listOf("Animation", "Comedy")), null))
        assertFalse(KidHold.isMadeForKids(film(null, genres = listOf("Animation")), null))
        assertFalse(KidHold.isAnimatedFamily(film("G", genres = listOf("Animation")), null))
    }

    @Test
    fun seriesLevelMatchesTheSameLists() {
        assertTrue(KidHold.isSeriesMadeForKids(show(genres = listOf("Kids"))))
        assertTrue(KidHold.isSeriesMadeForKids(show(studios = listOf("Noggin"))))
        assertTrue(KidHold.isSeriesMadeForKids(show("TV-Y7")))
        assertTrue(KidHold.isSeriesMadeForKids(show("TV-G", genres = animatedFamily)))
        assertTrue(KidHold.isSeriesMadeForKids(show(null, genres = animatedFamily)))
        assertFalse(KidHold.isSeriesMadeForKids(show("TV-G")))
        assertFalse(KidHold.isSeriesMadeForKids(show("TV-14", genres = animatedFamily)))
        assertFalse(KidHold.isSeriesMadeForKids(show(genres = listOf("Family"))))
        assertFalse(KidHold.isSeriesMadeForKids(show(genres = listOf("Drama"))))
    }

    @Test
    fun theVersionIsKidHoldThree() {
        assertEquals("kid-hold-3", KidHold.VERSION_NAME)
    }

    // --- the frozen manifest ---

    // A change to either list is a new version name: the hold's behaviour is versioned by it.
    @Test
    fun theManifestIsFrozenUnderItsVersionName() {
        assertEquals("kid-hold-3", KidHold.VERSION_NAME)
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

    // --- the children's-rating predicate, shared with the grown-up hold ---

    // A change to the set is a new version name of BOTH holds: this test names both.
    @Test
    fun theChildrensRatingsAreFrozenUnderBothHoldVersions() {
        assertEquals("kid-hold-3", KidHold.VERSION_NAME)
        assertEquals("grown-up-hold-1", GrownUpHold.VERSION_NAME)
        assertEquals(setOf("TV-Y", "TV-Y7", "TV-Y7-FV"), KidHold.CHILDRENS_RATINGS)
    }

    @Test
    fun hasChildrensRatingReadsTheItemAndItsKnownSeries() {
        assertTrue(KidHold.hasChildrensRating(film(rating = "TV-Y"), null))
        assertTrue(KidHold.hasChildrensRating(film(rating = "TV-Y7"), null))
        assertTrue(KidHold.hasChildrensRating(film(rating = "TV-Y7-FV"), null))
        assertTrue(KidHold.hasChildrensRating(episodeOf(rating = "TV-PG"), show(rating = "TV-Y7")))
        assertFalse(KidHold.hasChildrensRating(episodeOf(rating = "TV-PG"), null))
        for (rating in listOf("TV-G", "G", "PG", "TV-PG", "TV-14", "PG-13", "R", "NR", "Approved", "", null)) {
            assertFalse(KidHold.hasChildrensRating(film(rating = rating), null), "rating $rating")
        }
    }

    @Test
    fun theMadeForKidsRatingsRuleIsTheSharedPredicate() {
        for (rating in listOf("TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "PG", null)) {
            val item = film(rating = rating)
            assertEquals(KidHold.hasChildrensRating(item, null), KidHold.isMadeForKids(item, null))
        }
        assertTrue(KidHold.isMadeForKids(episodeOf(rating = "TV-PG"), show(rating = "TV-Y")))
    }

    // --- kidsChoiceFor ---

    @Test
    fun aKidsChoiceNeverLetsThroughWhatTheStoredCeilingHeldBack() {
        val stored =
            listOf(
                "TV-Y",
                "TV-G",
                "G",
                "TV-Y7",
                "TV-Y7-FV",
                "PG",
                "TV-PG",
                "PG-13",
                "TV-14",
                "R",
                "TV-MA",
                "NC-17",
                "TV-X",
                "Approved",
                "NR",
                "Bogus",
                "",
                null,
            )
        val probes = listOf("TV-Y", "G", "TV-Y7", "PG", "TV-PG", "PG-13", "R", "Approved", "NR")
        for (s in stored) {
            val choice = KidHold.kidsChoiceFor(s)
            assertTrue(choice in KidHold.CEILING_CHOICES, "$s -> $choice")
            for (p in probes) {
                if (KidHold.passesKidCeiling(film(p), null, choice)) {
                    assertTrue(KidHold.passesKidCeiling(film(p), null, s), "$p airs under $choice but not under $s")
                }
            }
        }
        assertEquals("G", KidHold.kidsChoiceFor("TV-G"))
        assertEquals("TV-Y7", KidHold.kidsChoiceFor("TV-Y7-FV"))
        assertEquals("PG", KidHold.kidsChoiceFor("TV-PG"))
        assertEquals("PG", KidHold.kidsChoiceFor(null))
        assertEquals("G", KidHold.kidsChoiceFor("Bogus"))
    }

    // --- ceilingChoices ---

    @Test
    fun everyCeilingChoiceLetsThroughExactlyTheRatingsUpToIt() {
        val ratings = listOf("TV-Y", "TV-G", "G", "TV-Y7", "TV-Y7-FV", "PG", "TV-PG", "PG-13", "R", "TV-MA")
        assertEquals(listOf(0, 1, 2), KidHold.CEILING_CHOICES.map { KidHold.kidRank(it) })
        for (choice in KidHold.CEILING_CHOICES) {
            val tier = checkNotNull(KidHold.kidRank(choice))
            for (r in ratings) {
                val expected = checkNotNull(KidHold.kidRank(r)) <= tier
                assertEquals(expected, KidHold.passesKidCeiling(film(r), null, choice), "$r under $choice")
            }
        }
    }
}
