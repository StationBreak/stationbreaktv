// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.rules.AutoSeed
import tv.stationbreak.core.rules.RatingLadder
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StarterCatalogTest {
    private val recipes = StarterCatalog.recipes
    private val leftovers = StarterCatalog.leftovers

    private val denyList =
        listOf(
            "ABC",
            "NBC",
            "CBS",
            "FOX",
            "HBO",
            "BBC",
            "Disney",
            "Nickelodeon",
            "Cartoon Network",
            "Cinemax",
            "Showtime",
            "Starz",
            "PBS",
            "AMC",
            "TCM",
            "Syfy",
            "MTV",
            "Discovery",
        )

    private fun sample(
        key: String = "sample",
        band: Band = Band.General,
        number: Int = 150,
        maxRating: String? = null,
        sources: List<Source> = listOf(Source.Genre("Comedy")),
    ) = Recipe(key, "Sample", "A sample.", band, number, false, sources, emptyList(), maxRating, null, emptyList())

    @Test
    fun theCatalogHasSeventyToAHundredRecipes() {
        assertTrue(recipes.size in 70..100, "size was ${recipes.size}")
        assertEquals(1, StarterCatalog.VERSION)
        assertEquals(1, StarterCatalog.version)
    }

    @Test
    fun keysAndNumbersAreUniqueAcrossRecipesAndLeftovers() {
        val keys = recipes.map { it.key } + leftovers.map { it.key }
        val numbers = recipes.map { it.number } + leftovers.map { it.number }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(numbers.size, numbers.toSet().size)
    }

    @Test
    fun noShippedKeyOrNumberIsRetired() {
        val keys = recipes.map { it.key } + leftovers.map { it.key }
        val numbers = recipes.map { it.number } + leftovers.map { it.number }
        assertTrue(keys.none { it in StarterCatalog.retiredKeys })
        assertTrue(numbers.none { it in StarterCatalog.retiredNumbers })
    }

    @Test
    fun namesAreUniqueIgnoringCase() {
        val names = recipes.map { it.name.lowercase() } + leftovers.map { it.name.lowercase() }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun everyNameAndPersonalityIsWritten() {
        assertTrue((recipes.map { it.name } + leftovers.map { it.name }).all { it.isNotBlank() })
        assertTrue((recipes.map { it.personality } + leftovers.map { it.personality }).all { it.isNotBlank() })
    }

    @Test
    fun everyBandKeepsAtLeastHalfItsNumbersFree() {
        val used = recipes.map { it.number } + leftovers.map { it.number }
        for (band in Band.entries) {
            val inBand = used.count { it in band.numbers }
            assertTrue(inBand * 2 <= band.numbers.count(), "$band uses $inBand of ${band.numbers.count()}")
        }
    }

    @Test
    fun everyBandHasRecipes() {
        for (band in Band.entries) assertTrue(recipes.any { it.band == band }, "$band is empty")
    }

    @Test
    fun kidRecipesCarryACeilingNoHigherThanPg() {
        val pg = checkNotNull(RatingLadder.rankOf("PG"))
        val kids = recipes.filter { it.band == Band.Kids }
        assertTrue(kids.isNotEmpty())
        for (r in kids) {
            assertTrue(r.kidSafe)
            val rank = RatingLadder.rankOf(r.maxRating)
            assertNotNull(rank, "${r.key} has no valid ceiling")
            assertTrue(rank <= pg, "${r.key} ceiling ${r.maxRating} is above PG")
        }
    }

    @Test
    fun grownUpRecipesCarryNoCeiling() {
        for (r in recipes.filter { it.band != Band.Kids }) {
            assertFalse(r.kidSafe)
            assertNull(r.maxRating, "${r.key} has a ceiling")
        }
    }

    @Test
    fun afterHoursIsTheOnlyOptInRecipe() {
        val optIn = recipes.filter { it.optIn }
        assertEquals(listOf("after-hours"), optIn.map { it.key })
        assertEquals(Band.Premium, optIn.single().band)
    }

    @Test
    fun theLeftoversChannelsAreWhereTheyShouldBe() {
        val kids = leftovers.single { it.key == "kids-odds-and-ends" }
        val grownUps = leftovers.single { it.key == "odds-and-ends" }
        assertEquals(2, leftovers.size)
        assertEquals(99, kids.number)
        assertEquals("PG", kids.maxRating)
        assertEquals(199, grownUps.number)
        assertNull(grownUps.maxRating)
    }

    @Test
    fun noNameContainsARealNetworkName() {
        val names = recipes.map { it.name } + leftovers.map { it.name }
        for (name in names) {
            for (bad in denyList) {
                assertFalse(name.contains(bad, ignoreCase = true), "$name contains $bad")
            }
        }
    }

    @Test
    fun everySourceIsPortableAndEveryKeyIsAValidRecipeKey() {
        for (r in recipes) {
            assertTrue(AutoSeed.isValidRecipeKey(r.key), r.key)
            assertTrue(r.sources.isNotEmpty())
            assertTrue((r.sources + r.exclusions).all { it is Source.Genre || it is Source.Tag || it is Source.YearRange })
        }
        for (l in leftovers) assertTrue(AutoSeed.isValidRecipeKey(l.key), l.key)
    }

    @Test
    fun recipeLooksUpByKey() {
        assertEquals("after-hours", StarterCatalog.recipe("after-hours")?.key)
        assertNull(StarterCatalog.recipe("no-such-recipe"))
    }

    @Test
    fun theCatalogIsAStarterCatalogView() {
        val view: StarterCatalogView = StarterCatalog
        assertEquals(recipes, view.recipes)
        assertEquals(leftovers, view.leftovers)
    }

    @Test
    fun aRecipeNeedsAValidKeyANumberInItsBandAndAPortableSource() {
        assertFailsWith<IllegalArgumentException> { sample(key = "Bad Key") }
        assertFailsWith<IllegalArgumentException> { sample(key = "") }
        assertFailsWith<IllegalArgumentException> { sample(number = 99) }
        assertFailsWith<IllegalArgumentException> { sample(sources = emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            sample(sources = listOf(Source.Library(CanonicalId.parse("0".repeat(32)))))
        }
    }

    @Test
    fun aRecipeIsKidSafeExactlyWhenItHasACeiling() {
        assertFailsWith<IllegalArgumentException> { sample(band = Band.Kids, number = 5, maxRating = null) }
        assertFailsWith<IllegalArgumentException> { sample(band = Band.General, number = 150, maxRating = "PG") }
        assertTrue(sample(band = Band.Kids, number = 5, maxRating = "G").kidSafe)
    }

    @Test
    fun lineupDefaultsAreTheDecidedValues() {
        assertEquals(43_200_000L, LineupDefaults.MIN_AIRABLE_RUNTIME_MS)
        assertEquals(50, LineupDefaults.CAP)
        assertEquals(5, LineupDefaults.FINALISE_THRESHOLD)
        assertEquals("06:00", LineupDefaults.DAY_START)
        assertEquals(600..899, LineupDefaults.CUSTOM_NUMBERS)
        assertEquals(900..999, LineupDefaults.RESERVED_NUMBERS)
    }
}
