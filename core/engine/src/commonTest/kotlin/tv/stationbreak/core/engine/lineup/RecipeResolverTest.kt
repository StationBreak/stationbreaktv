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
    fun resolveSetsNameSeedSourcesCeilingAndTiming() {
        val r = recipe(maxRating = "PG", band = Band.Kids, number = 10, ordering = OrderingMode.Chronological)
        val rules = RecipeResolver.resolve(r, server, -300, noCollections)
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
        assertNull(RecipeResolver.resolve(recipe(), server, 0, noCollections).ordering)
    }

    @Test
    fun hintMatchesEveryCollectionWithThatName() {
        val names =
            CollectionNames(
                mapOf(idOf(3) to "KAIJU", idOf(1) to "kaiju", idOf(2) to "Other", idOf(4) to "Kaiju"),
            )
        val rules = RecipeResolver.resolve(recipe(hints = listOf("Kaiju", "kaiju")), server, 0, names)
        val hinted = listOf(1, 3, 4).map { Source.Collection(idOf(it)) }
        assertEquals(recipe().sources + hinted, rules.sources)
    }

    @Test
    fun noMatchingCollectionAddsNothing() {
        val names = CollectionNames(mapOf(idOf(1) to "Other"))
        val rules = RecipeResolver.resolve(recipe(hints = listOf("Kaiju")), server, 0, names)
        assertEquals(recipe().sources, rules.sources)
    }

    @Test
    fun kidRecipesBlockUnratedAndUnknown() {
        val kids = StarterCatalog.recipes.filter { it.kidSafe }
        assertTrue(kids.isNotEmpty())
        for (kid in kids) {
            val rules = RecipeResolver.resolve(kid, server, 0, noCollections)
            val items = badItemsMatching(kid)
            val snap = snapshot(items = items.sortedBy { it.item.id.value })
            // Not vacuous: without the ceiling the recipe's sources do select the items.
            val open = PoolEvaluator.evaluate(rules.copy(maxRating = null, exclusions = null), snap)
            assertEquals(items.size, open.items.size, "${kid.key}: the synthetic items must match the sources")
            val pool = PoolEvaluator.evaluate(rules, snap)
            assertEquals(emptyList(), pool.items, "${kid.key}: the ceiling must block every bad item")
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
        val expected = RecipeResolver.resolve(equivalent, server, 345, noCollections).toSchedulerSettings()
        assertEquals(expected, RecipeResolver.leftoversSettings(left, server, 345))
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
