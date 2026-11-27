// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.LineupLimits
import tv.stationbreak.core.rules.AutoSeed
import tv.stationbreak.core.rules.RatingLadder
import tv.stationbreak.core.rules.RulesLimits
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
            "Treehouse",
        )

    private fun sample(
        key: String = "sample",
        band: Band = Band.General,
        number: Int = 150,
        maxRating: String? = null,
        sources: List<Source> = listOf(Source.Genre("Comedy")),
    ) = Recipe(key, "Sample", "A sample.", band, number, false, sources, emptyList(), maxRating, null, emptyList())

    // The lineup file is sized for MAX_CATALOG_STARTERS starters and KEEPER_COPIES previous copies (ADR-0023): a later
    // catalog that outgrows the bound would break the file bound silently, so it fails here instead.
    @Test
    fun theCatalogFitsTheLineupFilesBound() {
        assertTrue(
            recipes.size <= RecipeResolver.MAX_CATALOG_STARTERS,
            "${recipes.size} recipes, the lineup file is sized for ${RecipeResolver.MAX_CATALOG_STARTERS}",
        )
    }

    // The per-copy budget is where it was before the keepers got their own bound: MAX_CATALOG_STARTERS went from 120
    // to 110 as KEEPER_COPIES went from 50 to 60, so (1 MiB / 2) / (110 + 60) is still 3,084 bytes. Moving either
    // number moves what every stored starter's rules may take.
    @Test
    fun thePerCopyBudgetIsStill3084Bytes() {
        assertEquals(110, RecipeResolver.MAX_CATALOG_STARTERS)
        assertEquals(60, LineupDefaults.KEEPER_COPIES)
        assertEquals(3_084, RecipeResolver.STARTER_RULES_BUDGET_BYTES)
        val copies = RecipeResolver.MAX_CATALOG_STARTERS + LineupDefaults.KEEPER_COPIES
        assertEquals((LineupLimits.MAX_FILE_BYTES / 2) / copies, RecipeResolver.STARTER_RULES_BUDGET_BYTES)
    }

    @Test
    fun theCatalogHasSeventyToAHundredRecipes() {
        assertTrue(recipes.size in 70..110, "size was ${recipes.size}")
        assertEquals(31, StarterCatalog.VERSION)
        assertEquals(31, StarterCatalog.version)
        assertEquals(CATALOG_V3_RECIPES, recipes, "the CATALOG_V3_RECIPES list is the live one (catalog 5 edits it)")
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
        val kids = recipes.filter { it.kidSafe }
        assertTrue(kids.isNotEmpty())
        assertTrue(kids.all { it.band == Band.Kids }, "a kids-only recipe is in the Kids band")
        for (r in kids) {
            val rank = RatingLadder.rankOf(r.maxRating)
            assertNotNull(rank, "${r.key} has no valid ceiling")
            assertTrue(rank <= pg, "${r.key} ceiling ${r.maxRating} is above PG")
        }
    }

    @Test
    fun grownUpRecipesCarryNoCeiling() {
        for (r in recipes.filter { !it.band.young }) {
            assertFalse(r.kidSafe)
            assertNull(r.maxRating, "${r.key} has a ceiling")
        }
    }

    // The young bands (#1002, ADR-0028): Kids 1 to 33 and Family 34 to 66 are capped at PG, Teen 67 to 99 at TV-14.
    @Test
    fun theYoungBandsAreAgeOrderedAndCapped() {
        assertEquals(1..33, Band.Kids.numbers)
        assertEquals(34..66, Band.Family.numbers)
        assertEquals(67..99, Band.Teen.numbers)
        assertEquals(100..199, Band.General.numbers)
        for (r in recipes.filter { it.band == Band.Family && !it.kidSafe }) assertEquals("PG", r.maxRating, r.key)
        for (r in recipes.filter { it.band == Band.Teen && !it.kidSafe }) assertEquals("TV-14", r.maxRating, r.key)
        assertEquals(setOf(33, 66, 99, 199), leftovers.map { it.number }.toSet())
        assertEquals(listOf("PG", "PG", "TV-14", null), leftovers.sortedBy { it.number }.map { it.maxRating })
        assertEquals(
            setOf(
                "big-screen-toons",
                "popcorn-matinee",
                "ink-and-paint",
                "starship-sunday",
                "prime-time-pals",
                "fireside",
            ),
            recipes
                .filter { it.band == Band.Family && it.key !in StarterCatalog.EVER_KID_SAFE_KEYS }
                .map { it.key }
                .toSet(),
        )
        // Paper Lantern (54) is dropped (#1002); the number is Ink & Paint's from catalog 23 (#1018).
        assertEquals(listOf("ink-and-paint"), recipes.filter { it.number == 54 }.map { it.key })
    }

    @Test
    fun everyRecipeResolvesWithinTheRulesListLimits() {
        for (r in recipes) {
            assertTrue(r.exclusions.size <= RulesLimits.MAX_LIST_ENTRIES, "${r.key} exclusions")
            // Building the rules checks the limits again, collection hints included.
            RecipeResolver.resolve(r, SERVER, 0, NO_NAMES, NO_LIBRARY)
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
        assertEquals(4, leftovers.size)
        assertEquals(33, kids.number)
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
            val all = r.sources + r.exclusions
            assertTrue(
                all.all { it is Source.Genre || it is Source.Tag || it is Source.YearRange || it is Source.Studio },
            )
        }
        for (l in leftovers) assertTrue(AutoSeed.isValidRecipeKey(l.key), l.key)
    }

    @Test
    fun recipeLooksUpByKey() {
        assertEquals("after-hours", StarterCatalog.recipe("after-hours")?.key)
        assertNull(StarterCatalog.recipe("no-such-recipe"))
    }

    // The planner selects by identity over themed recipes: the shipped catalog is themed throughout.
    @Test
    fun theShippedCatalogIsThemedThroughout() {
        assertTrue(StarterCatalog.recipes.all { it.theme != null })
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
        assertFailsWith<IllegalArgumentException> { sample(band = Band.Family, number = 40, maxRating = null) }
        assertFailsWith<IllegalArgumentException> { sample(band = Band.General, number = 150, maxRating = "PG") }
        assertTrue(sample(band = Band.Kids, number = 5, maxRating = "G").kidSafe)
    }

    @Test
    fun aKidRecipeCeilingIsOnTheLadderAndNoHigherThanPg() {
        fun kids(rating: String) = sample(band = Band.Kids, number = 5, maxRating = rating)
        for (rating in listOf("TV-Y", "G", "TV-Y7-FV", "TV-PG", "PG")) {
            assertEquals(rating, kids(rating).maxRating)
        }
        for (rating in listOf("PG-13", "TV-14", "R", "TV-MA", "pg", "PG-14", "")) {
            assertFailsWith<IllegalArgumentException>(rating) { kids(rating) }
        }
    }

    @Test
    fun lineupDefaultsAreTheDecidedValues() {
        assertEquals(43_200_000L, LineupDefaults.MIN_AIRABLE_RUNTIME_MS)
        assertEquals(52, LineupDefaults.CAP)
        assertEquals(5, LineupDefaults.FINALISE_THRESHOLD)
        assertEquals("06:00", LineupDefaults.DAY_START)
        assertEquals(600..899, LineupDefaults.CUSTOM_NUMBERS)
        assertEquals(900..999, Band.Seasonal.numbers)
    }

    @Test
    fun aNonPortableExclusionIsRejected() {
        val library = Source.Library(CanonicalId.parse("0".repeat(32)))
        assertFailsWith<IllegalArgumentException> {
            val sources = listOf(Source.Genre("Comedy"))
            Recipe("x", "X", "x", Band.General, 150, false, sources, listOf(library), null, null, emptyList())
        }
    }

    @Test
    fun leftoversAreStrictAboutNumberAndCeiling() {
        fun left(
            number: Int,
            rating: String?,
        ) = LeftoversRecipe("l", "L", "l", number, rating)
        left(33, "PG")
        left(66, "PG")
        left(99, "PG-13")
        left(99, "TV-14")
        left(199, null)
        assertFailsWith<IllegalArgumentException> { left(250, null) }
        assertFailsWith<IllegalArgumentException> { left(33, null) }
        assertFailsWith<IllegalArgumentException> { left(33, "PG-13") }
        assertFailsWith<IllegalArgumentException> { left(66, "PG-13") }
        assertFailsWith<IllegalArgumentException> { left(99, "R") }
        assertFailsWith<IllegalArgumentException> { left(99, null) }
        assertFailsWith<IllegalArgumentException> { left(33, "bogus") }
        assertFailsWith<IllegalArgumentException> { left(199, "PG") }
        assertFailsWith<IllegalArgumentException> { LeftoversRecipe("Bad Key", "L", "l", 33, "PG") }
    }

    @Test
    fun noRecipeListsTwoSourcesThatDifferOnlyByCase() {
        for (r in recipes) {
            for (kind in listOf("genre", "tag")) {
                val names =
                    (r.sources + r.exclusions).mapNotNull {
                        when {
                            kind == "genre" && it is Source.Genre -> it.name
                            kind == "tag" && it is Source.Tag -> it.name
                            else -> null
                        }
                    }
                val folded = names.map { n -> foldCase(n) }
                assertEquals(folded.size, folded.toSet().size, "${r.key} has case-only duplicates among $names")
            }
        }
    }

    private fun foldCase(text: String): String = text.lowercase()
}
