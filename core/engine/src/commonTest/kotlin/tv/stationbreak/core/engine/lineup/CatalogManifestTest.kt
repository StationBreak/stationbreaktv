// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.KidHold
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.rules.FilterKind
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The shipped catalog keeps the promises frozen in every manifest of [CATALOG_MANIFESTS]. */
class CatalogManifestTest {
    private val shipped = StarterCatalog.recipes
    private val first = shipped.first()

    // A plain recipe (catalog 2's first): one whose hints and ordering a test may change, which a themed one refuses.
    private val plainFirst = CATALOG_V2_RECIPES.first()

    private val moviesOnly = Filters(null, null, null, null, null, FilterKind.Movie)

    private fun catalog(
        recipes: List<Recipe> = shipped,
        version: Int = 4,
        leftovers: List<LeftoversRecipe> = StarterCatalog.leftovers,
        retiredKeys: Set<String> = emptySet(),
        retiredNumbers: Set<Int> = emptySet(),
    ) = TestCatalog(recipes, version, leftovers, retiredNumbers, retiredKeys)

    private fun withFirst(change: (Recipe) -> Recipe) = shipped.map { if (it == first) change(it) else it }

    private fun problems(catalog: StarterCatalogView) = manifestProblems(catalog, CATALOG_MANIFEST_V4)

    @Test
    fun theShippedCatalogKeepsEveryManifest() {
        for (manifest in CATALOG_MANIFESTS) {
            assertEquals(emptyList(), manifestProblems(StarterCatalog, manifest), "manifest v${manifest.version}")
        }
        val versions = CATALOG_MANIFESTS.map { it.version }
        assertEquals(versions.sorted().distinct(), versions, "manifests are oldest first, one per version")
        assertEquals(StarterCatalog.VERSION, CATALOG_MANIFESTS.last().version, "the current version is frozen too")
        assertEquals(StarterCatalog.recipes.size, CATALOG_MANIFEST_V4.recipes.size)
        assertEquals(1, CATALOG_MANIFEST_V1.version)
        assertEquals(2, CATALOG_MANIFEST_V2.version)
        assertEquals(3, CATALOG_MANIFEST_V3.version)
        assertEquals(4, CATALOG_MANIFEST_V4.version)
    }

    // Catalog 4 (the spread day end) changed no recipe and no leftovers: the bump only moves starters to format 5.
    @Test
    fun versionFourIsVersionThreeEntryForEntry() {
        assertEquals(CATALOG_MANIFEST_V3.recipes, CATALOG_MANIFEST_V4.recipes)
        assertEquals(CATALOG_MANIFEST_V3.leftovers, CATALOG_MANIFEST_V4.leftovers)
    }

    // The catalog swap (spec 23) keeps every promise but what the recipes air: the same keys, numbers, bands, opt-in
    // flags and ceilings, and the same leftovers, so no ceiling a channel is scheduled under moves at the swap.
    @Test
    fun versionThreeKeepsEveryKeyNumberBandAndCeiling() {
        assertEquals(CATALOG_MANIFEST_V2.recipes.keys, CATALOG_MANIFEST_V3.recipes.keys)
        for ((key, old) in CATALOG_MANIFEST_V2.recipes) {
            val now = CATALOG_MANIFEST_V3.recipes.getValue(key)
            assertEquals(old.number, now.number, key)
            assertEquals(old.band, now.band, key)
            assertEquals(old.optIn, now.optIn, key)
            assertEquals(old.maxRating, now.maxRating, key)
            // Every recipe is themed and programmed now, so each airs differently.
            assertNotEquals(old.fingerprint, now.fingerprint, key)
        }
        assertEquals(CATALOG_MANIFEST_V2.leftovers, CATALOG_MANIFEST_V3.leftovers)
        // Catalog 2's recipes as kept in the code are still what manifest V2 froze.
        val v2 = TestCatalog(CATALOG_V2_RECIPES, version = 2, leftovers = StarterCatalog.leftovers)
        assertEquals(emptyList(), manifestProblems(v2, CATALOG_MANIFEST_V2))
    }

    @Test
    fun versionTwoKeepsEveryVersionOneKeyNumberAndBand() {
        for ((key, old) in CATALOG_MANIFEST_V1.recipes) {
            val now = CATALOG_MANIFEST_V2.recipes[key]
            assertNotNull(now, "$key kept in v2")
            assertEquals(old.number, now.number, key)
            assertEquals(old.band, now.band, key)
            assertEquals(old.optIn, now.optIn, key)
            assertEquals(old.maxRating, now.maxRating, key)
            // Only the grown-up recipes changed what they air.
            assertEquals(old.band == Band.Kids, old.fingerprint == now.fingerprint, key)
        }
        assertEquals(CATALOG_MANIFEST_V1.leftovers, CATALOG_MANIFEST_V2.leftovers)
    }

    // Catalog 2's promise (catalog 3's is in CatalogV3Test, with the anime exclusions added).
    @Test
    fun everyGrownUpRecipeExcludesTheKidsGenresAndKidRecipesKeepTheirSources() {
        // Exactly the spellings the kid hold recognises, so a grown-up channel drops what a kid channel would take.
        val kidsGenres = KidHold.KIDS_GENRES.map { Source.Genre(it) }
        for (recipe in CATALOG_V2_RECIPES) {
            if (recipe.band == Band.Kids) {
                assertEquals(emptyList(), recipe.exclusions, recipe.key)
            } else {
                assertEquals(kidsGenres, recipe.exclusions, recipe.key)
            }
        }
        // A kid recipe of catalog 2 still fingerprints as it did at version 1, tags included.
        for ((key, old) in CATALOG_MANIFEST_V1.recipes.filterValues { it.band == Band.Kids }) {
            assertEquals(old.fingerprint, fingerprintOf(CATALOG_V2_RECIPES.first { it.key == key }), key)
        }
        assertTrue(CATALOG_V2_RECIPES.first { it.key == "little-farm" }.sources.any { it is Source.Tag })
    }

    @Test
    fun catalogSourcesCarryNoExtras() {
        for (recipe in StarterCatalog.recipes) {
            assertTrue((recipe.sources + recipe.exclusions).all { it.extras.isEmpty() }, recipe.key)
        }
    }

    @Test
    fun theKidsLeftoversKeyIsAlwaysShippedWithACeiling() {
        val kids = StarterCatalog.leftovers.single { it.key == "kids-odds-and-ends" }
        assertNotNull(kids.maxRating)
        assertTrue("kids-odds-and-ends" !in StarterCatalog.retiredKeys)
        CATALOG_MANIFESTS.forEach { assertNotNull(it.leftovers.getValue("kids-odds-and-ends").maxRating) }
    }

    @Test
    fun theFingerprintIsAFixedFunctionOfTheCanonicalText() {
        // Worked out independently of this code, so the JVM and linuxX64 must both give exactly these.
        val sources = listOf(Source.Genre("Sci-Fi"), Source.Tag("café"), Source.YearRange(1980, 1989))
        val recipe = recipeOf("alpha", 110, hints = listOf("Kaiju"), sources = sources)
        assertEquals("a31d4dbdbabab50a", fingerprintOf(recipe))
        assertEquals("23dbb92882d87475", fingerprintOf(recipe.copy(ordering = OrderingMode.Shuffle)))
    }

    @Test
    fun whatARecipeAirsIsInTheFingerprint() {
        val base = fingerprintOf(plainFirst)
        val changes =
            listOf(
                plainFirst.copy(sources = plainFirst.sources + Source.Genre("Extra")),
                plainFirst.copy(sources = plainFirst.sources.reversed()),
                plainFirst.copy(exclusions = listOf(Source.Tag("scary"))),
                plainFirst.copy(collectionHints = plainFirst.collectionHints + "More"),
                plainFirst.copy(ordering = OrderingMode.Chronological),
            )
        changes.forEach { assertNotEquals(base, fingerprintOf(it), it.toString()) }
        // Length prefixes keep two texts from reading as one.
        assertNotEquals(
            fingerprintOf(plainFirst.copy(collectionHints = listOf("ab", "c"))),
            fingerprintOf(plainFirst.copy(collectionHints = listOf("a", "bc"))),
        )
        // A themed recipe's theme and filters are in it too.
        val themed = fingerprintOf(first)
        val theme = checkNotNull(first.theme)
        val anchor = Anchor(AnchorKind.Collection, NameMatcher.Exact("Box Set 1"))
        assertNotEquals(themed, fingerprintOf(first.copy(theme = theme.copy(anchors = theme.anchors + anchor))))
        assertNotEquals(themed, fingerprintOf(first.copy(theme = theme.copy(animeOnly = !theme.animeOnly))))
        assertNotEquals(themed, fingerprintOf(first.copy(filters = moviesOnly)))
    }

    @Test
    fun everKidSafeKeysHoldEveryKidSafeKeyOfTheCatalogAndEveryManifest() {
        val current =
            StarterCatalog.recipes.filter { it.maxRating != null }.map { it.key } +
                StarterCatalog.leftovers.filter { it.maxRating != null }.map { it.key }
        assertTrue(current.isNotEmpty())
        assertTrue(StarterCatalog.EVER_KID_SAFE_KEYS.containsAll(current), "the current catalog")
        for (manifest in CATALOG_MANIFESTS) {
            val kept =
                manifest.recipes.filterValues { it.maxRating != null }.keys +
                    manifest.leftovers.filterValues { it.maxRating != null }.keys
            assertTrue(StarterCatalog.EVER_KID_SAFE_KEYS.containsAll(kept), "manifest ${manifest.version}")
        }
        assertTrue("kids-odds-and-ends" in StarterCatalog.EVER_KID_SAFE_KEYS)
    }

    @Test
    fun theEverKidSafeKeysAreStillTheVersionOneSet() {
        val v1 = CATALOG_MANIFEST_V1
        val expected =
            v1.recipes.filterValues { it.maxRating != null }.keys +
                v1.leftovers.filterValues { it.maxRating != null }.keys
        assertEquals(expected, StarterCatalog.EVER_KID_SAFE_KEYS)
    }

    @Test
    fun aChangeToWhatARecipeAirsNeedsANewerVersion() {
        val anchor = Anchor(AnchorKind.Collection, NameMatcher.Exact("Box Set 1"))
        val changed =
            listOf(
                withFirst { it.copy(sources = it.sources + Source.Genre("Extra")) },
                withFirst { it.copy(theme = it.theme?.copy(anchors = it.theme.anchors + anchor)) },
                withFirst { it.copy(filters = moviesOnly) },
                withFirst { it.copy(maxRating = "G") },
                shipped + recipeOf("brand-new", 598),
            )
        for (recipes in changed) {
            assertTrue(problems(catalog(recipes)).isNotEmpty(), "caught at version 4")
            assertEquals(emptyList(), problems(catalog(recipes, version = 5)), "allowed at version 5")
        }
        val stricter = StarterCatalog.leftovers.map { if (it.number == 99) it.copy(maxRating = "G") else it }
        assertTrue(problems(catalog(leftovers = stricter)).isNotEmpty())
        assertEquals(emptyList(), problems(catalog(leftovers = stricter, version = 5)))
    }

    @Test
    fun aShippedKeyStaysAtItsNumberOrIsRetiredWithIt() {
        val moved = withFirst { it.copy(number = it.number + 1) }
        assertTrue(problems(catalog(moved, version = 5)).isNotEmpty(), "a shipped number never changes")

        val dropped = shipped.drop(1)
        assertTrue(problems(catalog(dropped, version = 5)).isNotEmpty(), "dropped without retiring")
        val keyOnly = catalog(dropped, version = 5, retiredKeys = setOf(first.key))
        assertTrue(problems(keyOnly).isNotEmpty(), "retired without its number")
        val retired = catalog(dropped, 5, retiredKeys = setOf(first.key), retiredNumbers = setOf(first.number))
        assertEquals(emptyList(), problems(retired))

        val noLeftovers = StarterCatalog.leftovers.filter { it.number != 199 }
        assertTrue(problems(catalog(leftovers = noLeftovers, version = 5)).isNotEmpty())
        val movedLeftovers = StarterCatalog.leftovers.map { if (it.number == 199) it.copy(number = 198) else it }
        assertTrue(problems(catalog(leftovers = movedLeftovers, version = 5)).isNotEmpty())
    }
}
