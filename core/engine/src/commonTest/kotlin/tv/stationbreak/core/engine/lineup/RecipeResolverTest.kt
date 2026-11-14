// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.airableRuntimeMs
import tv.stationbreak.core.engine.episode
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.movie
import tv.stationbreak.core.engine.snapshot
import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.schedule.LeftoverPolicy
import tv.stationbreak.core.rules.AutoSeed
import tv.stationbreak.core.rules.AutoSeedKind
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeResolverTest {
    private val server = idOf(0x5E)
    private val noCollections = CollectionNames(emptyMap())

    private fun recipe(
        hints: List<String> = emptyList(),
        ordering: OrderingMode? = null,
        maxRating: String? = null,
        band: Band = Band.General,
        number: Int = 110,
    ) = Recipe(
        key = "test-channel",
        name = "Test Channel",
        personality = "Synthetic.",
        band = band,
        number = number,
        optIn = false,
        sources = listOf(Source.Genre("Comedy"), Source.Tag("sitcom")),
        exclusions = listOf(Source.Genre("Horror")),
        maxRating = maxRating,
        ordering = ordering,
        collectionHints = hints,
    )

    @Test
    fun everyCatalogTwoStarterResolvesAgainstAnEmptyLibraryAtFormat5() {
        // Catalog 2's recipes, which lineups from before the swap hold, are plain (they need only 1), and are
        // written at 5 all the same: a resolved starter has the floor of a new channel (the spread day end).
        for (r in CATALOG_V2_RECIPES) {
            val rules = RecipeResolver.resolve(r, server, 0, noCollections, NO_LIBRARY)
            assertEquals(1, rules.requiredVersion, r.key)
            assertEquals(5, rules.formatVersion, r.key)
            assertEquals(r.sources, rules.sources, r.key)
        }
    }

    @Test
    fun everyCatalogStarterResolvesAgainstAnEmptyLibraryAtFormat5() {
        // With no names in the library no anchor or theme studio adds a source.
        for (r in StarterCatalog.recipes) {
            val rules = RecipeResolver.resolve(r, server, 0, noCollections, NO_LIBRARY)
            assertEquals(5, rules.formatVersion, r.key)
            assertEquals(OrderingMode.Curated, rules.orderingMode, r.key)
            assertEquals(r.sources, rules.sources, r.key)
        }
    }

    @Test
    fun resolveSetsNameSeedSourcesCeilingAndTiming() {
        val r = recipe(maxRating = "PG", band = Band.Kids, number = 10, ordering = OrderingMode.Chronological)
        val rules = RecipeResolver.resolve(r, server, -300, noCollections, NO_LIBRARY)
        assertEquals("Test Channel", rules.name)
        assertEquals(AutoSeed.derive(server.value, AutoSeedKind.Recipe, "test-channel"), rules.seed)
        assertEquals(r.sources, rules.sources)
        assertEquals(r.exclusions, rules.exclusions)
        assertEquals("PG", rules.maxRating)
        assertEquals(OrderingMode.Chronological, rules.orderingMode)
        assertEquals("06:00", rules.timing.broadcastDay.start)
        assertEquals(-300, rules.timing.broadcastDay.utcOffsetMinutes)
        assertNull(rules.timing.graceHours)
        assertNull(rules.timing.epochHours)
    }

    @Test
    fun noOrderingLeavesTheFieldOut() {
        assertNull(RecipeResolver.resolve(recipe(), server, 0, noCollections, NO_LIBRARY).ordering)
    }

    @Test
    fun hintMatchesEveryCollectionWithThatName() {
        val names =
            CollectionNames(
                mapOf(idOf(3) to "KAIJU", idOf(1) to "kaiju", idOf(2) to "Other", idOf(4) to "Kaiju"),
            )
        val rules = RecipeResolver.resolve(recipe(hints = listOf("Kaiju", "kaiju")), server, 0, names, NO_LIBRARY)
        val hinted = listOf(1, 3, 4).map { Source.Collection(idOf(it)) }
        assertEquals(recipe().sources + hinted, rules.sources)
    }

    @Test
    fun noMatchingCollectionAddsNothing() {
        val names = CollectionNames(mapOf(idOf(1) to "Other"))
        val rules = RecipeResolver.resolve(recipe(hints = listOf("Kaiju")), server, 0, names, NO_LIBRARY)
        assertEquals(recipe().sources, rules.sources)
    }

    @Test
    fun kidRecipesBlockUnratedAndUnknown() {
        val kids = StarterCatalog.recipes.filter { it.kidSafe }
        assertTrue(kids.isNotEmpty())
        for (kid in kids) {
            val rules = RecipeResolver.resolve(kid, server, 0, noCollections, NO_LIBRARY)
            val items = badItemsMatching(kid)
            val snap = snapshot(items = items.sortedBy { it.item.id.value })
            // Not vacuous: without the ceiling (and the filters) the recipe's sources do select the items.
            val open = PoolEvaluator.evaluate(rules.copy(maxRating = null, exclusions = null, filters = null), snap)
            assertEquals(items.size, open.items.size, "${kid.key}: the synthetic items must match the sources")
            val pool = PoolEvaluator.evaluate(rules, snap)
            assertEquals(emptyList(), pool.items, "${kid.key}: the ceiling must block every bad item")
            // The ceiling alone, not a filter of the recipe (catalog 3 has some), blocks them.
            val unfiltered = PoolEvaluator.evaluate(rules.copy(filters = null), snap)
            assertEquals(emptyList(), unfiltered.items, "${kid.key}: the ceiling alone blocks every bad item")
        }
    }

    // One item per source and per bad rating, so every source of the recipe is exercised.
    private fun badItemsMatching(kid: Recipe): List<RuleItem> {
        val ratings = listOf("", "NR", "Not Rated", "pg-13", null)
        var n = 0x1000
        val out = ArrayList<RuleItem>()
        for (source in kid.sources) {
            val genres = (source as? Source.Genre)?.let { listOf(it.name) }.orEmpty()
            val tags = (source as? Source.Tag)?.let { listOf(it.name) }.orEmpty()
            val year = (source as? Source.YearRange)?.from
            for (rating in ratings) {
                out += movie(idOf(n++), rating = rating, genres = genres, tags = tags, year = year)
            }
            // A well-rated episode of a series the snapshot does not have is still blocked.
            val ghostSeries = idOf(0x9000 + n)
            out += episode(idOf(n++), seriesId = ghostSeries, rating = "G", genres = genres, tags = tags, year = year)
        }
        return out
    }

    @Test
    fun leftoversSettingsEqualsAnEquivalentResolvedRecipe() {
        val left = StarterCatalog.leftovers.first { it.number == 199 }
        val equivalent = recipe().copy(key = left.key, ordering = null)
        val resolved = RecipeResolver.resolve(equivalent, server, 345, noCollections, NO_LIBRARY).toSchedulerSettings()
        // Held once recipes resolve at 5 too (the spread day end): a leftovers channel always gathers.
        val expected = resolved.copy(leftover = LeftoverPolicy.Gather)
        assertEquals(expected, RecipeResolver.leftoversSettings(left, server, 345))
    }

    @Test
    fun leftoversGather() {
        for (left in StarterCatalog.leftovers) {
            assertEquals(LeftoverPolicy.Gather, RecipeResolver.leftoversSettings(left, server, 0).leftover)
        }
    }

    // Task 6 (the spread day end): every starter is written at 5, a floor over ADR-0026's write version, so a studio
    // starter is at 5 (and matched under studio fold 2) whatever the format 4 gate says.
    @Test
    fun everyStarterResolvesAtVersion5() {
        val plain = RecipeResolver.resolve(recipe(), server, 0, noCollections, NO_LIBRARY)
        assertEquals(5, plain.formatVersion)
        assertTrue(plain.spreadsDayEnd)
        val studio = recipe().copy(sources = listOf(Source.Studio("Studio A")))
        for (gate in listOf(false, true)) {
            val rules = RecipeResolver.resolve(studio, server, 0, noCollections, NO_LIBRARY, gatePassed = gate)
            assertEquals(5, rules.formatVersion, "gate $gate")
            assertTrue(rules.matchesStudiosByFold2, "gate $gate")
        }
        // Every catalog starter, as the app resolves it, and a studio one among them.
        val studioFilm = movie(idOf(1), studios = listOf("Hanna-Barbera"))
        val names = LibraryNames(snapshot(items = listOf(studioFilm)), noCollections)
        var studios = 0
        for (r in StarterCatalog.recipes) {
            val rules = RecipeResolver.resolve(r, server, 0, noCollections, names)
            assertEquals(5, rules.formatVersion, r.key)
            assertTrue(rules.spreadsDayEnd, r.key)
            if (rules.usesStudios) studios++
        }
        assertTrue(studios > 0, "a catalog starter with a studio feature")
    }

    @Test
    fun airableRuntimeSumsOnlyAirableItems() {
        val settings = RecipeResolver.leftoversSettings(StarterCatalog.leftovers.first(), server, 0)
        val ok = movie(idOf(1)).item.copy(runtimeMs = 1_000_000L)
        val tooShort = ok.copy(id = idOf(2), runtimeMs = 59_999L)
        val tooLong = ok.copy(id = idOf(3), runtimeMs = settings.epochMs + 1)
        val edge = ok.copy(id = idOf(4), runtimeMs = settings.epochMs)
        assertEquals(1_000_000L + settings.epochMs, airableRuntimeMs(listOf(ok, tooShort, tooLong, edge), settings))
    }
}
