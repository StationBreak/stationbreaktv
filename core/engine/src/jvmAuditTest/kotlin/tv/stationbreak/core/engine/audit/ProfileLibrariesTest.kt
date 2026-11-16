// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // Test data: the sizes are the profiles'.

package tv.stationbreak.core.engine.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfileLibrariesTest {
    private val cases =
        CaseTitles.SMALL_HORROR_SHELF +
            listOf(
                CaseTitles.ADULT_ANIMATED_SITCOM,
                CaseTitles.SITCOM_WITH_AN_EXTRA_GENRE,
                CaseTitles.SITCOM_WITH_A_FREED_GENRE,
                CaseTitles.PG_ANIMATED_FAMILY_FILM,
                CaseTitles.PG_ANIMATED_FILM_NOT_FAMILY,
                CaseTitles.ADULT_CARTOON_WITH_FAMILY,
            )

    private fun titlesOf(key: String): Int {
        val s = ProfileLibraries.build(key).input.snapshot
        return s.series.size + s.items.count { it.item.seriesId == null }
    }

    private fun namesOf(key: String): List<String> {
        val s = ProfileLibraries.build(key).input.snapshot
        val films = s.items.filter { it.item.seriesId == null }.mapNotNull { it.name }
        return s.series.values.mapNotNull { it.name } + films
    }

    @Test
    fun theSizesAreTheOnesTheProfilesNameAndTheCountsAreTitlesNotItems() {
        assertEquals(1_900, titlesOf("broad-1900"))
        assertEquals(200, titlesOf("thin-200"))
        for (key in ProfileLibraries.keys) assertTrue(titlesOf(key) > 0, key)
    }

    @Test
    fun aProfileIsTheSameEveryTimeAndSurvivesTheFile() {
        for (key in ProfileLibraries.keys) {
            val text = AuditSnapshotCodec.write(ProfileLibraries.build(key).input)
            assertEquals(text, AuditSnapshotCodec.write(ProfileLibraries.build(key).input), key)
            val back = assertIs<SnapshotResult.Ok>(AuditSnapshotCodec.read(text)).input.snapshot
            assertEquals(ProfileLibraries.build(key).input.snapshot, back, key)
        }
    }

    @Test
    fun everyTitleIsInventedFromTheSyllables() {
        for (key in ProfileLibraries.keys) {
            val names = namesOf(key)
            assertEquals(titlesOf(key), names.size, key)
            assertTrue(names.all { InventedNames.isInvented(it) }, "$key: a title is not invented")
        }
        assertTrue(cases.all { InventedNames.isInvented(it) }, "A case title is not invented")
    }

    @Test
    fun theCasesAreCarriedByTheLibrariesThatTestThem() {
        val sitcom = ProfileLibraries.build("sitcom-household").cases.flatMap { it.titles }
        assertEquals(
            listOf(
                CaseTitles.SITCOM_WITH_AN_EXTRA_GENRE,
                CaseTitles.ADULT_ANIMATED_SITCOM,
                CaseTitles.SITCOM_WITH_A_FREED_GENRE,
                CaseTitles.PG_ANIMATED_FAMILY_FILM,
                CaseTitles.PG_ANIMATED_FILM_NOT_FAMILY,
                CaseTitles.ADULT_CARTOON_WITH_FAMILY,
            ),
            sitcom,
        )
        assertEquals(
            CaseTitles.SMALL_HORROR_SHELF,
            ProfileLibraries
                .build("horror-fan")
                .cases
                .single()
                .titles,
        )
        for (key in listOf("broad-1900", "thin-200")) {
            val titles = ProfileLibraries.build(key).cases.flatMap { it.titles }
            assertTrue(CaseTitles.ADULT_ANIMATED_SITCOM in titles, key)
            assertTrue(CaseTitles.SITCOM_WITH_AN_EXTRA_GENRE in titles, key)
        }
        val thin = ProfileLibraries.build("thin-200").cases.flatMap { it.titles }
        assertTrue(thin.containsAll(CaseTitles.SMALL_HORROR_SHELF))
    }

    @Test
    fun theAdultAnimatedSitcomHasNoStudioMatchAndNoAdultAnimationTag() {
        val s = ProfileLibraries.build("sitcom-household").input.snapshot
        val row = s.series.values.single { it.name == CaseTitles.ADULT_ANIMATED_SITCOM }
        assertEquals(listOf("Animation", "Comedy"), row.genres)
        assertTrue(row.tags.isEmpty())
        assertEquals("TV-MA", row.officialRating)
        assertNotNull(row.studios.singleOrNull())
    }

    @Test
    fun theSitcomWithAnExtraGenreHasOneBeyondComedyFamilyAndRomance() {
        val s = ProfileLibraries.build("sitcom-household").input.snapshot
        val row = s.series.values.single { it.name == CaseTitles.SITCOM_WITH_AN_EXTRA_GENRE }
        val sitcomGenres = setOf("Comedy", "Family", "Romance")
        assertEquals(listOf("Comedy"), row.genres.filter { it in sitcomGenres })
        assertEquals(1, row.genres.count { it !in sitcomGenres })
    }

    @Test
    fun theFreedGenreSitcomAndThePgAnimatedFilmsAreWhatTheyClaim() {
        val s = ProfileLibraries.build("sitcom-household").input.snapshot
        val sitcom = s.series.values.single { it.name == CaseTitles.SITCOM_WITH_A_FREED_GENRE }
        assertEquals(listOf("Comedy", "Drama"), sitcom.genres)
        assertTrue("sitcom" in sitcom.tags)
        val names = setOf(CaseTitles.PG_ANIMATED_FAMILY_FILM, CaseTitles.PG_ANIMATED_FILM_NOT_FAMILY)
        val films = s.items.filter { it.name in names }
        assertEquals(2, films.size)
        assertTrue(films.all { it.officialRating == "PG" && "Animation" in it.genres })
        val family = films.single { it.name == CaseTitles.PG_ANIMATED_FAMILY_FILM }
        assertTrue("Family" in family.genres)
        assertTrue("Family" !in films.single { it.name == CaseTitles.PG_ANIMATED_FILM_NOT_FAMILY }.genres)
    }
}
