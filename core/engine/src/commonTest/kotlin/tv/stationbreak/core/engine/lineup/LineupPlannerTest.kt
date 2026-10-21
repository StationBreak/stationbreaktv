// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.collection
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.RuleSnapshot
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
        assertTrue(lineup.channels.all { it.onSinceMs == 77L && it.numberOverride == null && it.nameOverride == null })
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

    @Test
    fun firstChoiceWaitsForTheCollectionPass() {
        val waiting = planOf(null, libraryOf(fills(), collectionsSynced = false), base, offset = -300)
        assertFalse(waiting.finalised, "no first choice before the collection pass")
        assertTrue(waiting.channels.isEmpty())
        assertEquals(-300, waiting.utcOffsetMinutes)

        val synced = planOf(waiting, libraryOf(fills(), collectionsSynced = true), base, offset = -240)
        assertTrue(synced.finalised)
        assertEquals(-240, synced.utcOffsetMinutes)
        assertEquals(five.map { it.first }.toSet() + "tots" + "kids-odds" + "odds", synced.onKeys())
    }

    @Test
    fun theFirstChoiceCanBeToldNotToWaitForTheCollectionPass() {
        val unsynced = libraryOf(fills(), collectionsSynced = false)
        val plain = LineupPlanner.plan(null, SERVER, USER, 0, unsynced, NO_NAMES, 1_000L, base)
        assertFalse(plain.finalised, "the default still waits")

        val told =
            LineupPlanner.plan(null, SERVER, USER, 0, unsynced, NO_NAMES, 1_000L, base, withoutCollections = true)
        assertTrue(told.finalised)
        assertEquals(five.map { it.first }.toSet() + "tots" + "kids-odds" + "odds", told.onKeys())
    }

    @Test
    fun theRefreshersBookkeepingIsCarriedThroughEveryPlan() {
        val kept =
            Lineup(SERVER, USER, 1, 0, false, emptyList(), collectionPassActedOn = 77L, syncsAwaitingCollections = 3)
        val waiting = planOf(kept, libraryOf(fills(), collectionsSynced = false), base)
        assertEquals(77L to 3, waiting.collectionPassActedOn to waiting.syncsAwaitingCollections)

        val finalised = planOf(kept, libraryOf(fills(), collectionsSynced = true), base)
        assertTrue(finalised.finalised)
        assertEquals(77L to 3, finalised.collectionPassActedOn to finalised.syncsAwaitingCollections)

        val later = planOf(finalised, libraryOf(fills(), collectionsSynced = true), base, nowMs = 9_000L)
        assertEquals(77L to 3, later.collectionPassActedOn to later.syncsAwaitingCollections)
    }

    @Test
    fun aRecipeThatFillsOnlyThroughItsCollectionIsOnAtFirstChoice() {
        // 60 recipes fill on their own, so the lineup is at the cap; "kaiju" fills only through its collection.
        val keys = List(60) { "r" + it.toString().padStart(2, '0') }
        val kaiju = recipeOf("kaiju", 320, hints = listOf("Kaiju"))
        val catalog = TestCatalog(keys.mapIndexed { i, k -> recipeOf(k, 200 + i) } + kaiju)
        val fill = keys.associateWith { QUALIFYING_ITEMS } + mapOf("kaiju" to 0, "monsters" to 4 * QUALIFYING_ITEMS)
        val library = libraryOf(fill, collectionsSynced = true)
        val members =
            library.items
                .filter { it.genres == listOf("monsters") }
                .map { it.item.id }
                .toSet()
        val kaijuId = idOf(0xC1)
        val withCollection = library.copy(collections = mapOf(kaijuId to collection(kaijuId, movies = members)))

        val without = planOf(null, library, catalog)
        assertEquals(ChannelState.Shelf, without.channel("kaiju").state, "shelved without its collection")

        val names = CollectionNames(mapOf(kaijuId to "Kaiju"))
        val lineup = planOf(null, withCollection, catalog, names = names)
        assertEquals(LineupDefaults.CAP, lineup.channels.count { it.state == ChannelState.On })
        assertEquals(ChannelState.On, lineup.channel("kaiju").state, "ranked first by its fill")
        assertEquals(
            kaiju.sources + Source.Collection(kaijuId),
            lineup
                .channel("kaiju")
                .starter.rules.sources,
        )
    }

    private val kaiju = recipeOf("kaiju", 320, hints = listOf("Kaiju"))
    private val mecha = recipeOf("mecha", 330, hints = listOf("Mecha"))
    private val hinted = TestCatalog(base.recipes + kaiju + mecha)
    private val kaijuId = idOf(0xC1)
    private val mechaId = idOf(0xC2)

    // Kaiju fills and is switched on; mecha does not fill and waits on the shelf.
    private fun hintedLibrary(synced: Boolean = true): RuleSnapshot {
        val fill = fills(mapOf("mecha" to 0), hinted)
        return libraryOf(fill, collectionsSynced = synced)
    }

    @Test
    fun aCollectionAppearingLaterReachesUneditedStarters() {
        val first = planOf(null, hintedLibrary(), hinted)
        assertEquals(
            kaiju.sources,
            first
                .channel("kaiju")
                .starter.rules.sources,
        )
        assertEquals(ChannelState.Shelf, first.channel("mecha").state)
        val seeded =
            first.withChannel("kaiju") {
                it.copy(source = it.starter.copy(rules = it.starter.rules.copy(seed = Seed(42uL))))
            }

        // The collections appear on a later synced call, with no catalog change.
        val names = CollectionNames(mapOf(kaijuId to "kaiju", mechaId to "MECHA"))
        val next = planOf(seeded, hintedLibrary(), hinted, nowMs = 2_000L, names = names)
        val rules = next.channel("kaiju").starter.rules
        assertEquals(kaiju.sources + Source.Collection(kaijuId), rules.sources)
        assertEquals(Seed(42uL), rules.seed, "the seed is kept")
        assertEquals(
            first
                .channel("kaiju")
                .starter.rules.timing,
            rules.timing,
            "the timing is kept",
        )
        assertEquals(first.channel("kaiju").onSinceMs, next.channel("kaiju").onSinceMs)
        val shelved = next.channel("mecha")
        assertEquals(ChannelState.Shelf, shelved.state)
        assertEquals(mecha.sources + Source.Collection(mechaId), shelved.starter.rules.sources, "a shelved starter too")
        // The same names again give the same rules, so nothing changes.
        assertEquals(next, planOf(next, hintedLibrary(), hinted, nowMs = 3_000L, names = names))
    }

    @Test
    fun anEditedStarterKeepsItsRulesWhenCollectionsAppear() {
        val first = planOf(null, hintedLibrary(), hinted)
        val editedRules =
            first
                .channel("kaiju")
                .starter.rules
                .copy(name = "Mine")
        val edited =
            first.withChannel("kaiju") {
                it.copy(source = ChannelSource.Starter("kaiju", edited = true, rules = editedRules))
            }
        val names = CollectionNames(mapOf(kaijuId to "Kaiju"))
        assertEquals(edited, planOf(edited, hintedLibrary(), hinted, nowMs = 2_000L, names = names))
    }

    @Test
    fun anUnsyncedCallChangesNoRules() {
        val first = planOf(null, hintedLibrary(), hinted)
        assertTrue(first.finalised)
        val names = CollectionNames(mapOf(kaijuId to "Kaiju", mechaId to "Mecha"))
        assertEquals(first, planOf(first, hintedLibrary(synced = false), hinted, nowMs = 2_000L, names = names))

        // Once synced the hints resolve; a later unsynced call that is missing the collections keeps them.
        val synced = planOf(first, hintedLibrary(), hinted, nowMs = 3_000L, names = names)
        assertEquals(
            kaiju.sources + Source.Collection(kaijuId),
            synced
                .channel("kaiju")
                .starter.rules.sources,
        )
        assertEquals(synced, planOf(synced, hintedLibrary(synced = false), hinted, nowMs = 4_000L))
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
    fun aLeftoversCeilingComesFromTheCatalogOnEveryCall() {
        val catalog = TestCatalog(base.recipes, leftovers = StarterCatalog.leftovers)
        val stored =
            firstLineup(catalog).withChannel("kids-odds-and-ends") {
                it.copy(source = ChannelSource.Leftovers("kids-odds-and-ends", null))
            }
        // The same catalog version, synced or not: the ceiling is the catalog's.
        for (synced in listOf(true, false)) {
            val next = planOf(stored, libraryOf(fills(), collectionsSynced = synced), catalog, nowMs = 2_000L)
            assertEquals(ChannelSource.Leftovers("kids-odds-and-ends", "PG"), next.channel("kids-odds-and-ends").source)
        }

        // A leftovers channel whose key left the catalog keeps the ceiling it has.
        val without = TestCatalog(base.recipes, leftovers = StarterCatalog.leftovers.filter { it.number != 99 })
        val kept = planOf(stored, libraryOf(fills()), without, nowMs = 2_000L)
        assertEquals(ChannelSource.Leftovers("kids-odds-and-ends", null), kept.channel("kids-odds-and-ends").source)
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
        assertEquals(5_000L, scifi.onSinceMs)
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
        assertEquals(lineup.channel("alpha").onSinceMs, next.channel("alpha").onSinceMs)
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
        assertEquals(9_000L, mars.onSinceMs)
        assertIs<ChannelSource.Starter>(nova.source)
    }

    @Test
    fun newerCatalogWaitsForCollections() {
        val lineup = firstLineup()
        val wider = listOf(Source.Genre("alpha"), Source.Tag("extra"))
        val v2 =
            TestCatalog(
                base.recipes.map { if (it.key == "alpha") recipeOf("alpha", 110, sources = wider) else it } +
                    recipeOf("mars", 160),
                version = 2,
            )
        val unsynced = planOf(lineup, libraryOf(fills(catalog = v2), collectionsSynced = false), v2, nowMs = 9_000L)
        assertEquals(1, unsynced.catalogVersion, "the catalog waits")
        assertEquals(lineup.channels, unsynced.channels, "no rules change and no recipe is added")

        val synced = planOf(unsynced, libraryOf(fills(catalog = v2)), v2, nowMs = 10_000L)
        assertEquals(2, synced.catalogVersion)
        assertEquals(
            wider,
            synced
                .channel("alpha")
                .starter.rules.sources,
        )
        assertEquals(ChannelState.On, synced.channel("mars").state)
        assertEquals(10_000L, synced.channel("mars").onSinceMs)
    }

    @Test
    fun newLeftoversRecipeTurnsOnWhenItsNumberIsFreeAndThereIsRoom() {
        val lineup = firstLineup().withChannel("alpha") { it.copy(numberOverride = 150) }
        val extra =
            listOf(
                LeftoversRecipe("more-odds", "More Odds", "Synthetic.", 198, null),
                LeftoversRecipe("taken-odds", "Taken Odds", "Synthetic.", 150, null),
            )
        val v2 = TestCatalog(base.recipes, version = 2, leftovers = TEST_LEFTOVERS + extra)
        val next = planOf(lineup, libraryOf(fills()), v2, nowMs = 9_000L)
        val more = next.channel("more-odds")
        assertEquals(ChannelState.On, more.state)
        assertEquals(198, more.number)
        assertEquals(ChannelSource.Leftovers("more-odds", null), more.source)
        assertEquals(9_000L, more.onSinceMs)
        val taken = next.channel("taken-odds")
        assertEquals(ChannelState.Shelf, taken.state, "its number is taken, so it waits for the user")
        assertNull(taken.number)
    }

    @Test
    fun newLeftoversRecipeAtCapWaitsOnShelf() {
        val keys = List(48) { "r" + it.toString().padStart(2, '0') }
        val catalog = TestCatalog(keys.mapIndexed { i, k -> recipeOf(k, 200 + i) })
        val fill = keys.associateWith { QUALIFYING_ITEMS }
        val lineup = planOf(null, libraryOf(fill), catalog)
        assertEquals(LineupDefaults.CAP, lineup.channels.count { it.state == ChannelState.On })
        val more = LeftoversRecipe("more-odds", "More Odds", "Synthetic.", 198, null)
        val v2 = TestCatalog(catalog.recipes, version = 2, leftovers = TEST_LEFTOVERS + more)
        val next = planOf(lineup, libraryOf(fill), v2)
        assertEquals(ChannelState.Shelf, next.channel("more-odds").state)
        assertEquals(198, next.channel("more-odds").number)
        assertEquals(LineupDefaults.CAP, next.channels.count { it.state == ChannelState.On })
    }

    @Test
    fun newerCatalogUpdatesALeftoversCeiling() {
        val lineup = firstLineup()
        val stricter = TEST_LEFTOVERS.map { if (it.key == "kids-odds") it.copy(maxRating = "G") else it }
        val v2 = TestCatalog(base.recipes, version = 2, leftovers = stricter)
        val next = planOf(lineup, libraryOf(fills()), v2)
        val kids = next.channel("kids-odds")
        assertEquals(ChannelSource.Leftovers("kids-odds", "G"), kids.source)
        assertEquals(lineup.channel("kids-odds").copy(source = kids.source), kids, "only the ceiling changes")
        assertEquals(lineup.channel("odds"), next.channel("odds"))
    }
}
