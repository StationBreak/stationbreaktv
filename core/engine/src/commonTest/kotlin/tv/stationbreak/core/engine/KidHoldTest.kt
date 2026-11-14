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

    // kid-hold-4 (ADR-0026): the studio fold 2 key has no spaces, so spacing between words no longer matters. Under
    // kid-hold-3 the fused "NetworkStudios" did not match.
    @Test
    fun spacingBetweenWordsDoesNotMatter() {
        assertTrue(KidHold.isMadeForKids(film(studios = listOf("Cartoon" + chars(9) + "Network Studios")), null))
        assertTrue(KidHold.isMadeForKids(film(studios = listOf("Cartoon" + chars(0x200B) + "NetworkStudios")), null))
        assertTrue(KidHold.isMadeForKids(film(studios = listOf("Cartoon" + chars(0xA0, 0x3000) + "Network")), null))
    }

    @Test
    fun aStudioNameOfOnlyFormatCharactersMatchesNothing() {
        assertFalse(KidHold.isMadeForKids(film(studios = listOf(chars(0x202E, 0x200B, 0x2066, 0xFEFF))), null))
        assertFalse(KidHold.isMadeForKids(film(studios = listOf(chars(0x200B), chars(9, 10))), null))
    }

    // --- The animated-family signal (added in kid-hold-3, unchanged by kid-hold-5 except that a known series' rating
    // decides): animated + Family + a rating that is G, TV-G or unrated ---

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
        assertFalse(KidHold.isMadeForKids(episodeOf(null), show("TV-PG", genres = animatedFamily)))
    }

    @Test
    fun anAnimatedFamilyEpisodeInAKidRatedSeriesIsMadeForKidsWhateverItsOwnRating() {
        // The series rating decides (kid-hold-5): a TV-G series makes its TV-14 episode animated family.
        assertTrue(KidHold.isMadeForKids(episodeOf("TV-14"), show("TV-G", genres = animatedFamily)))
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
    fun theVersionIsKidHoldFive() {
        assertEquals("kid-hold-5", KidHold.VERSION_NAME)
    }

    // --- the frozen manifest ---

    // A change to either list is a new version name: the hold's behaviour is versioned by it.
    @Test
    fun theManifestIsFrozenUnderItsVersionName() {
        assertEquals("kid-hold-5", KidHold.VERSION_NAME)
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
        assertEquals("kid-hold-5", KidHold.VERSION_NAME)
        assertEquals("grown-up-hold-4", GrownUpHold.VERSION_NAME)
        assertEquals(setOf("TV-Y", "TV-Y7", "TV-Y7-FV"), KidHold.CHILDRENS_RATINGS)
    }

    @Test
    fun theChildrensRatingRuleReadsTheItemAndItsKnownSeries() {
        assertTrue(KidHold.isMadeForKids(film(rating = "TV-Y"), null))
        assertTrue(KidHold.isMadeForKids(film(rating = "TV-Y7"), null))
        assertTrue(KidHold.isMadeForKids(film(rating = "TV-Y7-FV"), null))
        assertTrue(KidHold.isMadeForKids(episodeOf(rating = "TV-PG"), show(rating = "TV-Y7")))
        assertFalse(KidHold.isMadeForKids(episodeOf(rating = "TV-PG"), null))
        for (rating in listOf("TV-G", "G", "PG", "TV-PG", "TV-14", "PG-13", "R", "NR", "Approved", "", null)) {
            assertFalse(KidHold.isMadeForKids(film(rating = rating), null), "rating $rating")
        }
    }

    // The signal's lists are frozen under both hold versions: a change is a new version name of each.
    @Test
    fun theAnimatedFamilyListsAreFrozenUnderBothHoldVersions() {
        assertEquals("kid-hold-5", KidHold.VERSION_NAME)
        assertEquals("grown-up-hold-4", GrownUpHold.VERSION_NAME)
        assertEquals("animation", AnimatedFamilySignal.GENRE_ANIMATION)
        assertEquals("family", AnimatedFamilySignal.GENRE_FAMILY)
        assertEquals(setOf("G", "TV-G", "TV-Y", "TV-Y7", "TV-Y7-FV", "Approved"), AnimatedFamilySignal.RATINGS)
        assertEquals(setOf("NR", "NOT RATED", "UNRATED"), AnimatedFamilySignal.UNRATED_SPELLINGS)
    }

    // The grown-up hold follows kid-hold's predicate, so the two version names move together: a pinned pair.
    @Test
    fun theHoldVersionNamesMoveTogether() {
        assertEquals(
            mapOf("kid-hold-5" to "grown-up-hold-4"),
            mapOf(KidHold.VERSION_NAME to GrownUpHold.VERSION_NAME),
        )
    }

    // The gate's behaviour is pinned under both version names: a change to any vector is a new version of each hold.
    @Test
    fun theGrownUpGateIsPinnedUnderBothHoldVersions() {
        assertEquals("kid-hold-5", KidHold.VERSION_NAME)
        assertEquals("grown-up-hold-4", GrownUpHold.VERSION_NAME)
        val animated = listOf("Animation", "Family")
        val drops =
            mapOf(
                "G animated family" to (film(rating = "G", genres = animated) to true),
                "TV-Y" to (film(rating = "TV-Y") to true),
                "PG kids genre" to (film(rating = "PG", genres = listOf("Kids")) to true),
                "PG kid studio" to (film(rating = "PG", studios = listOf("Pixar")) to true),
                "unrated animated family" to (film(rating = null, genres = animated) to false),
                "NR animated family" to (film(rating = "NR", genres = animated) to false),
                "TV-PG kid studio" to (film(rating = "TV-PG", studios = listOf("Pixar")) to false),
                "R kids genre" to (film(rating = "R", genres = listOf("Kids")) to false),
                "live-action family PG" to (film(rating = "PG", genres = listOf("Family")) to false),
                "R drama" to (film(rating = "R") to false),
            )
        for ((name, pair) in drops) {
            assertEquals(pair.second, GrownUpHold.dropsFromGrownUp(pair.first, null), name)
        }
        // An episode: the series is read; a missing series fails the kid ceiling, so it is kept.
        assertTrue(GrownUpHold.dropsFromGrownUp(episodeOf(rating = "TV-Y7"), show(rating = "TV-Y7")))
        assertFalse(GrownUpHold.dropsFromGrownUp(episodeOf(rating = "TV-Y7"), null))
        // kid-hold-5: the series rating decides, so an off-rated episode of a TV-Y7 series drops too.
        assertTrue(GrownUpHold.dropsFromGrownUp(episodeOf(rating = "TV-PG"), show(rating = "TV-Y7")))
    }

    @Test
    fun theUnratedSpellingsTrimAllWhitespace() {
        val genres = listOf("Animation", "Family")
        for (rating in listOf("\tUNRATED", " not rated\n", "\u000Bnr ")) {
            assertTrue(KidHold.isAnimatedFamily(film(rating, genres = genres), null), "rating $rating")
        }
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

    @Test
    fun aKnownRatedSeriesDecidesForItsEpisodes() {
        assertTrue(KidHold.passesKidCeiling(episodeOf("TV-PG"), show("TV-Y"), "PG"))
        assertTrue(KidHold.passesKidCeiling(episodeOf("NR"), show("TV-Y"), "PG"))
        assertTrue(KidHold.passesKidCeiling(episodeOf("TV-14"), show("TV-Y7"), "TV-Y7"))
        assertFalse(KidHold.passesKidCeiling(episodeOf("TV-Y"), show("TV-PG"), "PG"))
        assertFalse(KidHold.passesKidCeiling(episodeOf("TV-Y"), show("NR"), "PG"))
        assertFalse(KidHold.passesKidCeiling(episodeOf("TV-Y"), show("Not Rated"), "PG"))
        assertFalse(KidHold.passesKidCeiling(episodeOf("G"), show("TV-Y7"), "G"))
    }

    @Test
    fun aKnownSeriesWithNoRatingFallsBackToTheEpisode() {
        assertFalse(KidHold.passesKidCeiling(episodeOf("TV-PG"), show(""), "PG"))
        assertFalse(KidHold.passesKidCeiling(episodeOf("TV-PG"), show(null), "PG"))
        assertFalse(KidHold.passesKidCeiling(episodeOf("TV-PG"), show("  "), "PG"))
        assertTrue(KidHold.passesKidCeiling(episodeOf("TV-Y"), show(null), "PG"))
    }

    @Test
    fun theGrownUpHoldDropsOffRatedEpisodesOfAKidsSeries() {
        val kids = show("TV-Y", genres = listOf("Children"))
        assertTrue(GrownUpHold.dropsFromGrownUp(episodeOf("TV-PG"), kids))
        assertTrue(GrownUpHold.dropsFromGrownUp(episodeOf("Unrated"), kids))
        assertFalse(GrownUpHold.dropsFromGrownUp(episodeOf("TV-PG"), show(null, genres = listOf("Children"))))
    }

    @Test
    fun theAnimatedFamilySignalTakesTheSeriesRatingWhenPresent() {
        val af = listOf("Animation", "Family")
        assertTrue(KidHold.isAnimatedFamily(episodeOf("PG", genres = af), show("TV-Y")))
        assertFalse(KidHold.isAnimatedFamily(episodeOf("TV-Y", genres = af), show("PG")))
        assertFalse(KidHold.isAnimatedFamily(episodeOf("PG", genres = af), show(" ")))
    }

    @Test
    fun aSeriesRatingIsAbsentOnlyWhenNullOrEmptyAfterTrimmingSpaces() {
        for (absent in listOf(null, "", " ", "   ")) assertTrue(KidHold.isAbsentRating(absent), "[$absent]")
        for (present in listOf("\t", "\u00A0", "\u3000", " \t ", "TV-Y")) {
            assertFalse(KidHold.isAbsentRating(present), present)
        }
    }

    @Test
    fun aSeriesRatingOfOtherWhitespaceIsPresentSoItFailsTheKidCeiling() {
        for (odd in listOf("\t", "\u00A0", "\u3000")) {
            assertFalse(KidHold.passesKidCeiling(episodeOf("TV-Y"), show(odd), "PG"), odd)
            assertTrue(KidHold.passesKidCeiling(episodeOf("TV-Y"), show("  "), "PG"))
        }
    }
}
