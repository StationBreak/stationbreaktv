// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.OrderingMode
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

    private fun catalog(
        recipes: List<Recipe> = shipped,
        version: Int = 1,
        leftovers: List<LeftoversRecipe> = StarterCatalog.leftovers,
        retiredKeys: Set<String> = emptySet(),
        retiredNumbers: Set<Int> = emptySet(),
    ) = TestCatalog(recipes, version, leftovers, retiredNumbers, retiredKeys)

    private fun withFirst(change: (Recipe) -> Recipe) = shipped.map { if (it == first) change(it) else it }

    private fun problems(catalog: StarterCatalogView) = manifestProblems(catalog, CATALOG_MANIFEST_V1)

    @Test
    fun theShippedCatalogKeepsEveryManifest() {
        for (manifest in CATALOG_MANIFESTS) {
            assertEquals(emptyList(), manifestProblems(StarterCatalog, manifest), "manifest v${manifest.version}")
        }
        val versions = CATALOG_MANIFESTS.map { it.version }
        assertEquals(versions.sorted().distinct(), versions, "manifests are oldest first, one per version")
        assertEquals(StarterCatalog.VERSION, CATALOG_MANIFESTS.last().version, "the current version is frozen too")
        assertEquals(StarterCatalog.recipes.size, CATALOG_MANIFEST_V1.recipes.size)
        assertEquals(1, CATALOG_MANIFEST_V1.version)
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
        val base = fingerprintOf(first)
        val changes =
            listOf(
                first.copy(sources = first.sources + Source.Genre("Extra")),
                first.copy(sources = first.sources.reversed()),
                first.copy(exclusions = listOf(Source.Tag("scary"))),
                first.copy(collectionHints = first.collectionHints + "More"),
                first.copy(ordering = OrderingMode.Chronological),
            )
        changes.forEach { assertNotEquals(base, fingerprintOf(it), it.toString()) }
        // Length prefixes keep two texts from reading as one.
        assertNotEquals(
            fingerprintOf(first.copy(collectionHints = listOf("ab", "c"))),
            fingerprintOf(first.copy(collectionHints = listOf("a", "bc"))),
        )
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
    fun aChangeToWhatARecipeAirsNeedsANewerVersion() {
        val changed =
            listOf(
                withFirst { it.copy(sources = it.sources + Source.Genre("Extra")) },
                withFirst { it.copy(collectionHints = it.collectionHints + "More") },
                withFirst { it.copy(ordering = OrderingMode.Chronological) },
                withFirst { it.copy(maxRating = "G") },
                shipped + recipeOf("brand-new", 598),
            )
        for (recipes in changed) {
            assertTrue(problems(catalog(recipes)).isNotEmpty(), "caught at version 1")
            assertEquals(emptyList(), problems(catalog(recipes, version = 2)), "allowed at version 2")
        }
        val stricter = StarterCatalog.leftovers.map { if (it.number == 99) it.copy(maxRating = "G") else it }
        assertTrue(problems(catalog(leftovers = stricter)).isNotEmpty())
        assertEquals(emptyList(), problems(catalog(leftovers = stricter, version = 2)))
    }

    @Test
    fun aShippedKeyStaysAtItsNumberOrIsRetiredWithIt() {
        val moved = withFirst { it.copy(number = it.number + 1) }
        assertTrue(problems(catalog(moved, version = 2)).isNotEmpty(), "a shipped number never changes")

        val dropped = shipped.drop(1)
        assertTrue(problems(catalog(dropped, version = 2)).isNotEmpty(), "dropped without retiring")
        val keyOnly = catalog(dropped, version = 2, retiredKeys = setOf(first.key))
        assertTrue(problems(keyOnly).isNotEmpty(), "retired without its number")
        val retired = catalog(dropped, 2, retiredKeys = setOf(first.key), retiredNumbers = setOf(first.number))
        assertEquals(emptyList(), problems(retired))

        val noLeftovers = StarterCatalog.leftovers.filter { it.number != 199 }
        assertTrue(problems(catalog(leftovers = noLeftovers, version = 2)).isNotEmpty())
        val movedLeftovers = StarterCatalog.leftovers.map { if (it.number == 199) it.copy(number = 198) else it }
        assertTrue(problems(catalog(leftovers = movedLeftovers, version = 2)).isNotEmpty())
    }
}
