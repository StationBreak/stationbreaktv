// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // The shape of a library is its data: counts, minutes and years are written as numbers.

package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.ExtrasFilter
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.LineupDefaults
import tv.stationbreak.core.engine.lineup.Season
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.RuleSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Catalog 31 (#1186): Fireside (Family 63, PG, Christmas season) homes the family-tier Christmas titles. Snowglobe
 * (Kids 20) is KidsOnly and caps at TV-Y7 whatever its label, so the G to TV-Y7 label change moves nothing the Tots
 * cap did not already allow, and the bare `holiday` tag no longer takes a summer-holiday film into any of the three
 * Christmas channels. The library is synthetic; every run is dated in the Christmas window.
 */
@Suppress("TooManyFunctions") // One small test per claim of the issue.
class FiresidePoolTest {
    private val library = LibraryBuilder(31)
    private val xmas = listOf("Christmas")
    private val kidGenres = listOf("Kids", "Animation")

    private fun film(
        genres: List<String>,
        rating: String?,
        tags: List<String> = xmas,
        minutes: Int = 100,
    ) = library.film(TitleSpec(genres, tags, emptyList(), rating, minutes))

    private val familyFilms = (1..8).map { film(listOf("Holiday"), "PG") }
    private val kidsSpecials = (1..8).map { film(kidGenres, "TV-Y7") }
    private val pgKidsEvidence = film(kidGenres, "PG")
    private val pg13 = film(listOf("Comedy"), "PG-13")
    private val unrated = film(listOf("Comedy"), null)
    private val summerHoliday = film(listOf("Holiday"), "PG", tags = listOf("holiday"))
    private val summerKidsHoliday = film(kidGenres, "TV-Y7", tags = listOf("holiday"))

    init {
        library.shows(12, listOf("Comedy"), "TV-PG", 22, listOf("sitcom"), 2, 6, 1994)
        library.shows(10, listOf("Drama"), "TV-14", 44, emptyList(), 2, 6, 2008)
    }

    private fun lanesOf(snapshot: RuleSnapshot): AuditLanes = AuditLanes(plan(ExtrasFilter.filter(snapshot).snapshot))

    private fun plan(snapshot: RuleSnapshot): PlannedLineup {
        val inSeason = snapshot.copy(cutoffMs = DEC_15_2026_MS)
        return PlannedLineup.of(inSeason, CollectionNames(emptyMap()), null)
    }

    private val lanes = lanesOf(library.build())

    private fun pool(key: String): Set<CanonicalId> {
        val lane = lanes.lanes.firstOrNull { it.channel.recipe.key == key } ?: return emptySet()
        return lane.cand.indices.mapTo(HashSet()) { lanes.titleOf(lane, it).id }
    }

    private val fireside = pool("fireside")
    private val snowglobe = pool("snowglobe")
    private val december = pool("always-december")

    private fun id(name: String) = library.titleIds.getValue(name)

    @Test
    fun aPgFamilyChristmasFilmIsOnFiresideAndNotOnSnowglobe() {
        for (name in familyFilms) {
            assertTrue(id(name) in fireside, "$name is not on Fireside")
            assertFalse(id(name) in snowglobe, "$name is on Snowglobe")
        }
        assertTrue(id(pgKidsEvidence) in fireside, "a PG title with kids evidence is Family tier, home on Fireside")
        assertFalse(id(pgKidsEvidence) in snowglobe, "a PG title never airs on a Tots channel")
    }

    @Test
    fun aTvY7KidsTierChristmasSpecialIsOnSnowglobeAndNotOnFireside() {
        for (name in kidsSpecials) {
            assertTrue(id(name) in snowglobe, "$name is not on Snowglobe")
            assertFalse(id(name) in fireside, "$name is on Fireside: the GrownUp policy drops the kids tier")
        }
    }

    @Test
    fun theFiresidePoolIsExactlyTheFamilyTierChristmasTitles() {
        assertEquals((familyFilms + pgKidsEvidence).map { id(it) }.toSet(), fireside, "no kids-tier title leaks in")
    }

    @Test
    fun aPg13OrUnratedChristmasTitleIsOnNeitherYoungChannel() {
        for (name in listOf(pg13, unrated)) {
            assertFalse(id(name) in fireside, "$name is on Fireside")
            assertFalse(id(name) in snowglobe, "$name is on Snowglobe")
            assertTrue(id(name) in december || name == unrated, "$name is not on Always December")
        }
    }

    @Test
    fun aTitleTaggedOnlyHolidayIsOnNoChristmasChannel() {
        for (name in listOf(summerHoliday, summerKidsHoliday)) {
            val inAny = listOf(fireside, snowglobe, december).any { id(name) in it }
            assertFalse(inAny, "$name is on a Christmas channel")
        }
    }

    @Test
    fun theWindowIsUnchangedAndTheBandAgreesWithTheCeiling() {
        assertEquals(LineupDefaults.CHRISTMAS_SEASON, Season.Christmas.window)
        assertEquals(11 to 22, Season.Christmas.window.startMonth to Season.Christmas.window.startDay)
        val recipe = StarterCatalog.recipes.single { it.key == "fireside" }
        assertEquals(Band.Family, recipe.band)
        assertEquals(63, recipe.number)
        assertEquals("PG", recipe.maxRating)
        assertEquals(Season.Christmas, recipe.season)
        assertFalse(recipe.kidSafe)
        assertEquals("TV-Y7", StarterCatalog.recipes.single { it.key == "snowglobe" }.maxRating)
        assertEquals(20, StarterCatalog.recipes.single { it.key == "snowglobe" }.number)
    }

    private fun stateOfFireside(titles: Int): ChannelState? {
        val lib = LibraryBuilder(32)
        repeat(titles) { lib.film(TitleSpec(listOf("Holiday"), xmas, emptyList(), "PG", 120)) }
        lib.shows(12, listOf("Comedy"), "TV-PG", 22, listOf("sitcom"), 2, 6, 1994)
        lib.shows(10, listOf("Drama"), "TV-14", 44, emptyList(), 2, 6, 2008)
        val planned = plan(lib.build())
        return planned.lineup.channels
            .firstOrNull { (it.source as? ChannelSource.Starter)?.recipeKey == "fireside" }
            ?.state
    }

    @Test
    fun theChannelQualifiesAtSixTitlesAndTwelveHoursAndNotAtFive() {
        assertEquals(ChannelState.On, stateOfFireside(6), "six 2-hour films are 12 hours and six titles")
        assertTrue(stateOfFireside(5) != ChannelState.On, "five titles are under the bar")
    }

    private companion object {
        // 2026-12-15 15:00 UTC, inside the Christmas window.
        const val DEC_15_2026_MS = 1_797_346_800_000L
    }
}
