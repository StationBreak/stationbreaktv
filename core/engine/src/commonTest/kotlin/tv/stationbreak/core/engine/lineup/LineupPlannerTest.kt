// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.collection
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LineupPlannerTest {
    // Five grown-up recipes and one kids recipe, all filling: enough to finalise.
    private val five = listOf("alpha" to 110, "bravo" to 120, "charlie" to 210, "delta" to 310, "echo" to 410)
    private val base = TestCatalog(five.map { (k, n) -> recipeOf(k, n) } + recipeOf("tots", 10))

    private fun fills(
        extra: Map<String, Int> = emptyMap(),
        catalog: StarterCatalogView = base,
    ): Map<String, Int> = catalog.recipes.associate { it.key to QUALIFYING_ITEMS } + extra

    private fun firstLineup(
        catalog: StarterCatalogView = base,
        extra: Map<String, Int> = emptyMap(),
        offset: Int = 0,
    ): Lineup {
        val lineup = planOf(null, libraryOf(fills(extra, catalog)), catalog, offset = offset)
        assertTrue(lineup.finalised, "the fixture lineup is finalised")
        return lineup
    }

    @Test
    fun firstSetupTurnsOnBestFillingUnderCap() {
        // 60 qualifying recipes in the Drama and Genre bands; r12 ties with r11 at the cut, and r11 wins by key.
        val keys = List(60) { "r" + it.toString().padStart(2, '0') }
        val catalog = TestCatalog(keys.mapIndexed { i, k -> recipeOf(k, 200 + i) })
        val fill = keys.mapIndexed { i, k -> k to QUALIFYING_ITEMS + if (i == 12) 11 else i }.toMap()
        val lineup = planOf(null, libraryOf(fill), catalog, nowMs = 77L)

        assertTrue(lineup.finalised)
        val on = lineup.channels.filter { it.state == ChannelState.On }
        assertEquals(LineupDefaults.CAP, on.size)
        val expectedOn = keys.drop(13).toSet() + "r11" + "kids-odds" + "odds"
        assertEquals(expectedOn, lineup.onKeys())
        assertEquals(ChannelState.Shelf, lineup.channel("r12").state)
        assertEquals(ChannelState.Shelf, lineup.channel("r00").state)
        // Every recipe is on the lineup at its default number; the list is in number order.
        assertEquals(62, lineup.channels.size)
        catalog.recipes.forEach { assertEquals(it.number, lineup.channel(it.key).defaultNumber) }
        assertEquals(lineup.channels.map { it.number }, lineup.channels.map { it.number }.sortedBy { it })
        assertTrue(lineup.channels.all { it.addedAtMs == 77L && it.numberOverride == null && it.nameOverride == null })
        assertEquals(1, lineup.catalogVersion)
        assertEquals(0, lineup.utcOffsetMinutes)
        assertEquals(SERVER, lineup.serverId)
        assertEquals(USER, lineup.userId)
        val r30 = lineup.channel("r30").starter
        assertFalse(r30.edited)
        assertEquals(RecipeResolver.resolve(catalog.recipes[30], SERVER, 0, NO_NAMES), r30.rules)
    }

    @Test
    fun fewQualifiersLeaveLineupUnfinalised() {
        val short = fills(mapOf("echo" to QUALIFYING_ITEMS - 1, "tots" to 0))
        val lineup = planOf(null, libraryOf(short), base, offset = -300)
        assertFalse(lineup.finalised)
        assertTrue(lineup.channels.none { it.state == ChannelState.On })
        assertEquals(-300, lineup.utcOffsetMinutes)

        // The next sync brings the fifth qualifier: now it finalises, at the standard offset of that call.
        val next = planOf(lineup, libraryOf(fills()), base, offset = -240)
        assertTrue(next.finalised)
        assertEquals(-240, next.utcOffsetMinutes)
        assertEquals(five.map { it.first }.toSet() + "tots" + "kids-odds" + "odds", next.onKeys())
    }

    private val kaiju = recipeOf("kaiju", 320, hints = listOf("Kaiju"))
    private val hinted = TestCatalog(base.recipes + kaiju)
    private val kaijuCollection = collection(idOf(0xC1), movies = setOf(idOf(0x10000)))

    @Test
    fun finalisesWithoutHintsThenResolvesThem() {
        val unsynced = libraryOf(fills(catalog = hinted), collectionsSynced = false)
        val first = planOf(null, unsynced, hinted)
        assertTrue(first.finalised, "a failing collection pass never leaves a household without channels")
        assertFalse(first.hintsResolvedWithCollections)
        assertEquals(
            kaiju.sources,
            first
                .channel("kaiju")
                .starter.rules.sources,
        )

        val synced = libraryOf(fills(catalog = hinted), collections = listOf(kaijuCollection))
        val names = CollectionNames(mapOf(kaijuCollection.id to "kaiju"))
        val second = planOf(first, synced, hinted, nowMs = 2_000L, names = names)
        assertTrue(second.hintsResolvedWithCollections)
        val rules = second.channel("kaiju").starter.rules
        assertEquals(kaiju.sources + Source.Collection(kaijuCollection.id), rules.sources)
        assertEquals(
            first
                .channel("kaiju")
                .starter.rules.seed,
            rules.seed,
        )
        assertEquals(
            first
                .channel("kaiju")
                .starter.rules.timing,
            rules.timing,
        )
        assertEquals(first.channel("kaiju").addedAtMs, second.channel("kaiju").addedAtMs)
    }

    @Test
    fun hintsResolveOnceCollectionsSync() {
        val synced = libraryOf(fills(catalog = hinted), collections = listOf(kaijuCollection))
        val names = CollectionNames(mapOf(kaijuCollection.id to "Kaiju"))
        val unsynced = planOf(null, libraryOf(fills(catalog = hinted), collectionsSynced = false), hinted)
        // An edited starter keeps its rules through the hint pass.
        val editedRules =
            unsynced
                .channel("alpha")
                .starter.rules
                .copy(name = "Mine")
        val withEdit =
            unsynced.withChannel("alpha") {
                it.copy(source = ChannelSource.Starter("alpha", edited = true, rules = editedRules))
            }
        val resolved = planOf(withEdit, synced, hinted, names = names)
        assertTrue(resolved.hintsResolvedWithCollections)
        assertEquals(editedRules, resolved.channel("alpha").starter.rules)
        assertEquals(
            1,
            resolved
                .channel("kaiju")
                .starter.rules.sources
                .count { it is Source.Collection },
        )

        // Once resolved, a new collection with the same name changes nothing until a catalog update.
        val more = CollectionNames(mapOf(kaijuCollection.id to "Kaiju", idOf(0xC2) to "KAIJU"))
        assertEquals(resolved, planOf(resolved, synced, hinted, names = more))
    }

    @Test
    fun leftoversAlwaysOnAt99And199() {
        val lineup = firstLineup()
        val kids = lineup.channel("kids-odds")
        assertEquals(LineupIds.leftovers("kids-odds"), kids.id)
        assertEquals(99, kids.number)
        assertEquals(ChannelState.On, kids.state)
        assertEquals(ChannelSource.Leftovers("kids-odds", "PG"), kids.source)
        val grown = lineup.channel("odds")
        assertEquals(LineupIds.leftovers("odds"), grown.id)
        assertEquals(199, grown.number)
        assertEquals(ChannelState.On, grown.state)
        assertEquals(ChannelSource.Leftovers("odds", null), grown.source)
        // They stay on when the library empties.
        val empty = planOf(lineup, libraryOf(emptyMap()), base)
        assertEquals(ChannelState.On, empty.channel("odds").state)
        assertEquals(ChannelState.On, empty.channel("kids-odds").state)
    }

    @Test
    fun optInNeverTurnsOnByItself() {
        val late = recipeOf("late", 510, optIn = true)
        val catalog = TestCatalog(base.recipes + late)
        // Four ordinary qualifiers and a full opt-in recipe: the opt-in does not count towards finalising.
        val four = fills(mapOf("echo" to 0, "tots" to 0, "late" to 500), catalog)
        assertFalse(planOf(null, libraryOf(four), catalog).finalised)

        val lineup = planOf(null, libraryOf(fills(mapOf("late" to 500), catalog)), catalog)
        assertTrue(lineup.finalised)
        assertEquals(ChannelState.Shelf, lineup.channel("late").state)
        val again = planOf(lineup, libraryOf(fills(mapOf("late" to 900), catalog)), catalog)
        assertEquals(ChannelState.Shelf, again.channel("late").state)
    }

    @Test
    fun newQualifierTurnsOnAtItsNumber() {
        val catalog = TestCatalog(base.recipes + recipeOf("scifi", 330))
        val lineup = firstLineup(catalog, mapOf("scifi" to QUALIFYING_ITEMS - 1))
        assertEquals(ChannelState.Shelf, lineup.channel("scifi").state)
        assertEquals(330, lineup.channel("scifi").number)

        val next = planOf(lineup, libraryOf(fills(catalog = catalog)), catalog, nowMs = 5_000L)
        val scifi = next.channel("scifi")
        assertEquals(ChannelState.On, scifi.state)
        assertEquals(330, scifi.number)
        assertEquals(5_000L, scifi.addedAtMs)
        assertEquals(RecipeResolver.resolve(catalog.recipes.last(), SERVER, 0, NO_NAMES), scifi.starter.rules)
        // Nothing else moved.
        assertEquals(lineup.channels.filter { it.id != scifi.id }, next.channels.filter { it.id != scifi.id })
    }

    @Test
    fun atCapNewQualifierWaitsOnShelf() {
        // 50 recipes: r00 falls short, the other 49 qualify and 48 of them fit beside the two leftovers channels.
        val keys = List(50) { "r" + it.toString().padStart(2, '0') }
        val catalog = TestCatalog(keys.mapIndexed { i, k -> recipeOf(k, 200 + i) })
        val fill = keys.mapIndexed { i, k -> k to if (i == 0) 1 else QUALIFYING_ITEMS + i }.toMap()
        val lineup = planOf(null, libraryOf(fill), catalog)
        assertEquals(LineupDefaults.CAP, lineup.channels.count { it.state == ChannelState.On })
        assertEquals(ChannelState.Shelf, lineup.channel("r01").state)

        val next = planOf(lineup, libraryOf(fill + ("r00" to 500)), catalog, nowMs = 9_000L)
        assertEquals(ChannelState.Shelf, next.channel("r00").state)
        assertEquals(lineup, next)
    }

    @Test
    fun userHiddenNeverReturns() {
        val catalog = TestCatalog(base.recipes + recipeOf("scifi", 330))
        val lineup =
            firstLineup(catalog, mapOf("scifi" to 0))
                .withChannel("alpha") { it.copy(state = ChannelState.UserHidden) }
                .withChannel("scifi") { it.copy(state = ChannelState.UserHidden) }
                .withChannel("odds") { it.copy(state = ChannelState.UserHidden) }
        val next = planOf(lineup, libraryOf(fills(mapOf("alpha" to 500, "scifi" to 500), catalog)), catalog)
        assertEquals(ChannelState.UserHidden, next.channel("alpha").state)
        assertEquals(ChannelState.UserHidden, next.channel("scifi").state)
        assertEquals(ChannelState.UserHidden, next.channel("odds").state)
    }

    @Test
    fun channelThatEmptiesStaysOn() {
        val lineup = firstLineup()
        val next = planOf(lineup, libraryOf(fills(mapOf("alpha" to 0, "bravo" to 0))), base, nowMs = 9_000L)
        assertEquals(ChannelState.On, next.channel("alpha").state)
        assertEquals(lineup, next)
    }

    @Test
    fun channelAddedAfterClockChangeUsesLineupOffset() {
        val catalog = TestCatalog(base.recipes + recipeOf("scifi", 330))
        val lineup = firstLineup(catalog, mapOf("scifi" to 0), offset = -300)
        assertEquals(-300, lineup.utcOffsetMinutes)
        // The device's standard offset is now -240; the lineup keeps the -300 it was set up with.
        val next = planOf(lineup, libraryOf(fills(catalog = catalog)), catalog, offset = -240)
        assertEquals(-300, next.utcOffsetMinutes)
        val scifi = next.channel("scifi")
        assertEquals(ChannelState.On, scifi.state)
        assertEquals(-300, scifi.starter.rules.timing.broadcastDay.utcOffsetMinutes)
    }

    @Test
    fun catalogUpdateRefreshesUneditedOnly() {
        val lineup =
            firstLineup()
                .withChannel("alpha") {
                    it.copy(source = it.starter.copy(rules = it.starter.rules.copy(seed = Seed(42uL))))
                }.withChannel("bravo") { it.copy(source = it.starter.copy(edited = true)) }
        val custom = ChannelSource.Custom(lineup.channel("charlie").starter.rules)
        val withCustom = lineup.withChannel("charlie") { it.copy(source = custom) }
        val wider = listOf(Source.Genre("alpha"), Source.Tag("extra"))
        val v2 =
            TestCatalog(
                base.recipes.map {
                    when (it.key) {
                        "alpha" -> recipeOf("alpha", 110, sources = wider)
                        "bravo" -> recipeOf("bravo", 120, sources = wider)
                        "charlie" -> recipeOf("charlie", 210, sources = wider)
                        else -> it
                    }
                },
                version = 2,
            )
        val next = planOf(withCustom, libraryOf(fills()), v2, nowMs = 9_000L)
        assertEquals(2, next.catalogVersion)
        val alpha = next.channel("alpha").starter
        assertEquals(wider, alpha.rules.sources)
        assertEquals(Seed(42uL), alpha.rules.seed, "the seed is kept")
        assertEquals(
            lineup
                .channel("alpha")
                .starter.rules.timing,
            alpha.rules.timing,
            "the timing is kept",
        )
        assertEquals(lineup.channel("bravo").source, next.channel("bravo").source)
        assertEquals(custom, next.channel("charlie").source)
        assertEquals(lineup.channel("alpha").addedAtMs, next.channel("alpha").addedAtMs)
    }

    @Test
    fun removedRecipeBecomesCustom() {
        val lineup = firstLineup()
        val v2 =
            TestCatalog(
                base.recipes.filter { it.key != "charlie" },
                version = 2,
                leftovers = TEST_LEFTOVERS.filter { it.key != "odds" },
            )
        val next = planOf(lineup, libraryOf(fills()), v2)
        val before = lineup.channel("charlie")
        val after = next.channel("charlie")
        assertEquals(LineupIds.starter("charlie"), after.id)
        assertEquals(ChannelSource.Custom(before.starter.rules), after.source)
        assertEquals(before.copy(source = after.source), after)
        assertEquals(lineup.channel("odds"), next.channel("odds"), "a removed leftovers channel is kept as it is")
    }

    @Test
    fun catalogDowngradeChangesNothing() {
        val catalog = TestCatalog(base.recipes + recipeOf("scifi", 330), version = 2)
        val lineup = firstLineup(catalog, mapOf("scifi" to 0))
        val older = TestCatalog(base.recipes, version = 1)
        val next = planOf(lineup, libraryOf(fills(mapOf("scifi" to 500), catalog), collectionsSynced = true), older)
        assertEquals(lineup, next)
        assertEquals(2, next.catalogVersion)
    }

    @Test
    fun newRecipeAtOccupiedNumberDoesNotCollide() {
        val lineup = firstLineup().withChannel("alpha") { it.copy(numberOverride = 150) }
        val v2 = TestCatalog(base.recipes + recipeOf("nova", 150) + recipeOf("mars", 160), version = 2)
        val next = planOf(lineup, libraryOf(fills(catalog = v2)), v2, nowMs = 9_000L)
        val nova = next.channel("nova")
        assertEquals(ChannelState.Shelf, nova.state)
        assertNull(nova.defaultNumber)
        assertNull(nova.number)
        assertEquals(150, next.channel("alpha").number)
        val mars = next.channel("mars")
        assertEquals(ChannelState.On, mars.state, "a new recipe at a free number that fills turns on")
        assertEquals(160, mars.number)
        assertEquals(9_000L, mars.addedAtMs)
        assertIs<ChannelSource.Starter>(nova.source)
    }
}
