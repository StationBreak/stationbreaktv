// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.ExtrasFilter
import tv.stationbreak.core.engine.KidHold
import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.collection
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.movie
import tv.stationbreak.core.engine.rules
import tv.stationbreak.core.engine.snapshot
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The catalog swap from version 2 to version 3 (spec 23, Task 23): a lineup from a file before version 3 has its
 * viewer's choices inferred once (A7, E7), its unedited starters take the new rules from the next boundary (D1), and
 * the selection runs; nothing of today changes, and the curation basis at the swap is empty (F2).
 */
class LineupPlannerMigrationTest {
    private val noon = 12 * 3_600_000L
    private val boundary = nextDayStart(noon)

    private val keys =
        listOf("tots" to 10, "alpha" to 110, "bravo" to 120, "charlie" to 210, "delta" to 310, "echo" to 410)
    private val plain = keys.map { (k, n) -> recipeOf(k, n) } + recipeOf("late", 520, optIn = true)
    private val v1 = TestCatalog(plain, version = 1)
    private val v2 = TestCatalog(plain, version = 2)

    private fun themed(recipe: Recipe): Recipe =
        recipe.copy(ordering = OrderingMode.Curated, theme = Theme(emptyList(), emptyList(), null, false))

    // Catalog 3: the same keys, numbers and bands, every recipe themed and also taking a genre nothing has, so its
    // rules change and its pool does not.
    private fun v3(change: (Recipe) -> Recipe = { it }): TestCatalog =
        TestCatalog(
            plain.map { themed(change(it.copy(sources = it.sources + Source.Genre(it.key + " v3")))) },
            version = 3,
            selectsByIdentity = true,
        )

    // Every channel fills; the runtimes rank tots, alpha, bravo, charlie, delta, echo.
    private val fills =
        mapOf("tots" to 40, "alpha" to 38, "bravo" to 36, "charlie" to 34, "delta" to 30, "echo" to 28, "late" to 30)

    // A lineup from a file before version 3: chosen at 1,000 under catalog 2, the viewer's switch-ons unknown.
    private fun before(
        catalog: StarterCatalogView = v2,
        library: RuleSnapshot = libraryOf(fills),
    ): Lineup = planOf(null, library, catalog).also { assertTrue(it.finalised) }.copy(viewerChoicesKnown = false)

    private fun swap(
        lineup: Lineup,
        library: RuleSnapshot = libraryOf(fills),
        catalog: StarterCatalogView = v3(),
    ): Lineup = planOf(lineup, library, catalog, nowMs = noon, boundaryMs = boundary)

    // [count] of the viewer's own channels, on, numbered from 600: each holds a place, so the old top is smaller.
    private fun Lineup.withCustoms(count: Int): Lineup =
        copy(
            channels =
                channels +
                    (0 until count).map {
                        LineupChannel(
                            ChannelId("custom:$it"),
                            600 + it,
                            null,
                            null,
                            ChannelState.On,
                            ChannelSource.Custom(rules(listOf(Source.Genre("mine $it")))),
                            1_000L,
                        )
                    },
        )

    @Test
    fun anOnOptInStarterReadsAsChosen() {
        val lineup = before().withChannel("late") { it.copy(state = ChannelState.On) }

        val next = swap(lineup)

        assertTrue(next.viewerChoicesKnown)
        assertTrue(next.channel("late").chosenByViewer)
        assertEquals(ChannelState.On, next.channel("late").state)
        assertNull(next.channel("late").leavesAtMs)
        assertFalse(next.channel("alpha").chosenByViewer, "a starter in the old top is the planner's")
    }

    // 44 of the viewer's own channels and the two leftovers leave room for four: the v2 planner would have picked
    // tots, alpha, bravo and charlie, so delta and echo, on all the same, are the viewer's.
    @Test
    fun anOnStarterOutsideTheOldTopReadsAsChosen() {
        val lineup = before().withCustoms(44)

        val next = swap(lineup)

        for (key in listOf("delta", "echo")) {
            assertTrue(next.channel(key).chosenByViewer, key)
            assertEquals(ChannelState.On, next.channel(key).state, key)
            assertNull(next.channel(key).leavesAtMs, "$key is pinned")
        }
        for (key in listOf("tots", "alpha", "bravo", "charlie")) assertFalse(next.channel(key).chosenByViewer, key)
        assertTrue(next.viewerChoicesKnown)
    }

    // The old top is what the v2 planner ranked: on the snapshot the planner reads, with the extras taken out (E7).
    // Echo's trailers would lift it above charlie; filtered, they are gone and echo is outside the top.
    @Test
    fun theOldTopIsRankedOnTheFilteredSnapshot() {
        val base = libraryOf(fills)
        val trailers =
            (0 until 40).map {
                val entry = movie(idOf(0x90000 + it), rating = "G", genres = listOf("echo"))
                entry.copy(item = entry.item.copy(runtimeMs = 300_000L), name = "Trailer $it")
            }
        val raw = base.copy(items = (base.items + trailers).sortedBy { it.item.id.value })
        val filtered = ExtrasFilter.filter(raw).snapshot
        assertEquals(base.items.size, filtered.items.size, "the filter takes the trailers out")
        val lineup = before().withCustoms(44)

        val next = swap(lineup, filtered)

        assertTrue(next.channel("echo").chosenByViewer, "outside the top on the filtered snapshot")
        assertFalse(next.channel("charlie").chosenByViewer)
        // On the raw snapshot echo would be in the top and charlie out: the snapshot decides.
        val unfiltered = swap(lineup, raw)
        assertFalse(unfiltered.channel("echo").chosenByViewer)
        assertTrue(unfiltered.channel("charlie").chosenByViewer)
    }

    @Test
    fun aLineupWhoseChoicesAreKnownInfersNothing() {
        val lineup = before().withCustoms(44).copy(viewerChoicesKnown = true)

        val next = swap(lineup)

        assertTrue(next.channels.none { it.chosenByViewer })
    }

    // D1: today keeps catalog 2. Every starter on the air, hidden or opt-in (what airs today, and what the leftovers
    // withhold today) keeps its v2 rules for today and takes the v3 rules at the boundary; no channel goes on or off
    // the air before it.
    @Test
    fun theCatalogSwapLeavesTodayUnchanged() {
        val lineup =
            before(real2, realLibrary)
                .withChannel("laugh-track") { it.copy(state = ChannelState.UserHidden, chosenByViewer = true) }

        val next = swap(lineup, realLibrary, StarterCatalog)

        assertEquals(3, next.catalogVersion)
        val byId = lineup.channels.associateBy { it.id }
        var held = 0
        for (channel in next.channels) {
            val old = checkNotNull(byId[channel.id]) { "no channel joins at this swap: ${channel.id}" }
            val onToday = old.state == ChannelState.On
            val nowOn = channel.state == ChannelState.On && channel.onSinceMs <= noon
            assertEquals(onToday, nowOn, "${channel.id} is on the air today exactly as before")
            val source = channel.source as? ChannelSource.Starter
            val optIn = source != null && checkNotNull(StarterCatalog.recipe(source.recipeKey)).optIn
            // What airs today, and what the leftovers withhold today (a hidden starter, an opt-in one).
            val readToday = onToday || old.state == ChannelState.UserHidden || optIn
            if (source != null && readToday) {
                val id = channel.id.value
                assertEquals(old.starter.rules, source.rulesFor(noon), "$id: today's rules are catalog 2's")
                assertEquals(OrderingMode.Curated, source.rulesFor(boundary).orderingMode, "$id from the boundary")
                assertEquals(boundary, source.rulesFromMs, id)
                held++
            }
        }
        assertTrue(held > 10, "starters on the air, hidden or opt-in: $held")
        assertEquals(leftoversOf(lineup), leftoversOf(next), "the leftovers channels are as they were")
    }

    @Test
    fun aCatalogTwoLineupMovesToThreeKeepingNumbersKeysAndSeeds() {
        val lineup = before(real2, realLibrary)

        val next = swap(lineup, realLibrary, StarterCatalog)

        assertEquals(3, next.catalogVersion)
        assertTrue(next.viewerChoicesKnown)
        assertEquals(lineup.channels.map { it.id }.toSet(), next.channels.map { it.id }.toSet())
        val byId = next.channels.associateBy { it.id }
        for (old in lineup.channels) {
            val now = byId.getValue(old.id)
            assertEquals(old.number, now.number, old.id.value)
            val source = old.source as? ChannelSource.Starter ?: continue
            assertEquals(source.rules.seed, now.starter.rules.seed, old.id.value)
            assertEquals(source.rules.timing, now.starter.rules.timing, old.id.value)
            assertEquals(source.recipeKey, now.starter.recipeKey)
            assertFalse(now.starter.edited)
            // Catalog 3's rules: programmed, from the boundary for one on the air.
            assertEquals(OrderingMode.Curated, now.starter.rules.orderingMode, old.id.value)
        }
    }

    // A starter of a file before version 2 that the planner put on (in the old top) is untouched: the selection
    // decides it again, for the days from the boundary. Under catalog 3 alpha has nothing, so it leaves then.
    @Test
    fun anUntouchedV1StarterIsReselectedAtTheBoundary() {
        val lineup = before(v1)
        val catalog = v3 { if (it.key == "alpha") it.copy(sources = listOf(Source.Genre("gone"))) else it }

        val next = swap(lineup, catalog = catalog)

        val alpha = next.channel("alpha")
        assertFalse(alpha.chosenByViewer)
        assertEquals(ChannelState.On, alpha.state, "on the air until the boundary")
        assertEquals(boundary, alpha.leavesAtMs)
        assertEquals(ShelfReason.Empty, alpha.shelfReason)
        assertEquals(lineup.channel("alpha").starter.rules, alpha.starter.rulesFor(noon))
        assertEquals(ChannelState.Shelf, next.rolled(boundary).channel("alpha").state)
    }

    // LOW-3 (Task 20): a starter the swap's selection picks off the shelf starts at the boundary, never mid-day.
    @Test
    fun aStarterPickedAtTheSwapStartsAtTheBoundary() {
        val withFoxtrot = TestCatalog(v2.recipes + recipeOf("foxtrot", 130), version = 2)
        // Foxtrot did not fill under catalog 2, so it waits on the shelf; under catalog 3 it takes "foxtrot new".
        val lineup = before(withFoxtrot)
        assertEquals(ChannelState.Shelf, lineup.channel("foxtrot").state)
        val catalog =
            TestCatalog(
                v3().recipes + themed(recipeOf("foxtrot", 130, sources = listOf(Source.Genre("foxtrot new")))),
                version = 3,
                selectsByIdentity = true,
            )
        val library = libraryOf(fills + ("foxtrot new" to QUALIFYING_ITEMS))

        val next = swap(lineup, library, catalog)

        val foxtrot = next.channel("foxtrot")
        assertEquals(ChannelState.On, foxtrot.state)
        assertEquals(boundary, foxtrot.onSinceMs, "on from the boundary, not from now")
        assertTrue(Source.Genre("foxtrot new") in foxtrot.starter.rules.sources)
    }

    @Test
    fun touchedChannelsKeepTheirState() {
        val edited = rules(listOf(Source.Genre("mine")))
        val lineup =
            before()
                .withChannel("bravo") { it.copy(state = ChannelState.UserHidden, chosenByViewer = true) }
                .withChannel("charlie") { it.copy(numberOverride = 299, chosenByViewer = true) }
                .withChannel("delta") {
                    it.copy(source = ChannelSource.Starter("delta", edited = true, rules = edited))
                }

        val next = swap(lineup)

        assertEquals(ChannelState.UserHidden, next.channel("bravo").state)
        val charlie = next.channel("charlie")
        assertEquals(299, charlie.number)
        assertEquals(ChannelState.On, charlie.state)
        assertNull(charlie.leavesAtMs, "the viewer's number pins it")
        assertSame(lineup.channel("delta"), next.channel("delta"), "an edited starter is never touched")
    }

    @Test
    fun customChannelsAreUntouched() {
        val lineup =
            before().withCustoms(3).let { l ->
                val hidden = ChannelId("custom:1")
                val channels = l.channels.map { if (it.id == hidden) it.copy(state = ChannelState.UserHidden) else it }
                l.copy(channels = channels)
            }

        val next = swap(lineup)

        for (custom in lineup.channels.filter { it.source is ChannelSource.Custom }) {
            assertEquals(custom, next.channels.single { it.id == custom.id })
        }
    }

    // F2: no day before the boundary has programmed rules, so today's curated on-set is empty; tomorrow's is not.
    @Test
    fun theBasisAtTheSwapIsEmpty() {
        val next = swap(before())

        assertEquals(CurationBasis(boundary, emptySet(), true), next.curationBasis)
        val programmed =
            next.channels.filter {
                it.state == ChannelState.On && it.leavesAtMs == null &&
                    (it.source as? ChannelSource.Starter)?.rules?.orderingMode == OrderingMode.Curated
            }
        assertTrue(programmed.isNotEmpty(), "the next day is curated")
    }

    // Every kid starter is held to made-for-kids content (kid-hold-5) under its v2 rules today and its v3 rules
    // from the boundary, as the planner and the day build read it.
    @Test
    fun kidChannelsOnlyEverSeeKidHoldFive() {
        assertEquals("kid-hold-5", KidHold.VERSION_NAME)
        val next = swap(before(real2, realLibrary), realLibrary, StarterCatalog)

        var checked = 0
        val kids =
            next.channels.filter {
                val source = it.source as? ChannelSource.Starter
                source != null && checkNotNull(StarterCatalog.recipe(source.recipeKey)).band == Band.Kids
            }
        assertTrue(kids.isNotEmpty())
        for (channel in kids) {
            val source = channel.starter
            val recipe = checkNotNull(StarterCatalog.recipe(source.recipeKey))
            assertEquals(PoolPolicy.KidsOnly, KidStatus.policyOf(channel, next), channel.id.value)
            assertEquals(PoolPolicy.KidsOnly, KidStatus.policyOf(recipe), channel.id.value)
            for (rules in listOf(source.rulesFor(noon), source.rules)) {
                val pool = PoolEvaluator.evaluate(rules, realLibrary, PoolPolicy.KidsOnly)
                for (item in pool.items) {
                    val entry = realLibrary.items.single { it.item.id == item.id }
                    assertTrue(KidHold.isMadeForKids(entry, null), "${channel.id}: ${item.id}")
                }
                checked += pool.items.size
            }
        }
        assertTrue(checked > 0, "no kid channel aired anything")
    }

    // While catalog 3 waits for a collection pass, a catalog-2 lineup is held: nothing switches on or off, no rules
    // change and its choices stay unknown, so the swap infers them from the lineup as it was.
    @Test
    fun aWaitingCatalogThreeHoldsACatalogTwoLineup() {
        val withFoxtrot = TestCatalog(v2.recipes + recipeOf("foxtrot", 130), version = 2)
        val lineup = before(withFoxtrot)
        val catalog =
            TestCatalog(v3().recipes + themed(recipeOf("foxtrot", 130)), version = 3, selectsByIdentity = true)
        // An incremental sync: foxtrot fills now, charlie is empty.
        val incremental =
            libraryOf(fills + ("foxtrot" to QUALIFYING_ITEMS) + ("charlie" to 0), collectionsSynced = false)

        val held = swap(lineup, incremental, catalog)

        assertEquals(2, held.catalogVersion)
        assertEquals(lineup.onKeys(), held.onKeys())
        assertTrue(held.channels.all { it.leavesAtMs == null })
        assertFalse(held.viewerChoicesKnown)
        for (channel in held.channels) {
            assertEquals(lineup.channels.single { it.id == channel.id }.source, channel.source, channel.id.value)
        }
    }

    // The keepers' copies stay within the file's bound when a newer catalog adds recipes: the room counts the starters
    // the plan ends with, the added ones included. 97 starters, half of them hidden, then a catalog of 120.
    @Test
    fun theKeeperRoomCountsTheRecipesANewerCatalogAdds() {
        val keysOf = (0 until 97).map { "r$it" to 200 + it }
        val older = TestCatalog(keysOf.map { (k, n) -> recipeOf(k, n) }, version = 2)
        val library = libraryOf(keysOf.associate { it.first to QUALIFYING_ITEMS })
        val chosen = planOf(null, library, older)
        val lineup =
            chosen.copy(
                channels =
                    chosen.channels.map {
                        val shelved = it.source is ChannelSource.Starter && it.state == ChannelState.Shelf
                        if (shelved) it.copy(state = ChannelState.UserHidden, chosenByViewer = true) else it
                    },
            )
        val hidden = lineup.channels.count { it.state == ChannelState.UserHidden }
        assertTrue(hidden >= 45, "$hidden hidden")
        val more = (0 until 23).map { recipeOf("n$it", 300 + it) }
        val newer =
            TestCatalog(older.recipes.map { it.copy(sources = it.sources + Source.Genre("new")) } + more, version = 3)

        val next = swap(lineup, library, newer)

        val starters = next.channels.filter { it.source is ChannelSource.Starter }
        assertEquals(120, starters.size)
        val copies = starters.size + starters.count { it.starter.previousRules != null }
        assertEquals(RecipeResolver.MAX_CATALOG_STARTERS + LineupDefaults.CAP, copies, "the room is used to the bound")
        // Without the added recipes in the count the room would be 73 and the copies 193.
        val withoutAdded =
            swap(lineup, library, TestCatalog(newer.recipes.take(97), version = 3))
                .channels
                .filter { it.source is ChannelSource.Starter }
        assertEquals(97 + 73, withoutAdded.size + withoutAdded.count { it.starter.previousRules != null })
    }

    // A starter whose rules are above the per-copy budget (an older app resolved without one) keeps no copy in any
    // keeper group, on the air or withholding: it takes the new rules at once (a known edge), and the file bound holds.
    @Test
    fun aStarterOverThePerCopyBudgetKeepsNoCopyInAnyGroup() {
        val oversized = rules((0 until 200).map { Source.Tag("an over-long tag of a much older rules file $it") })
        assertTrue(RecipeResolver.fileBytes(oversized) > RecipeResolver.STARTER_RULES_BUDGET_BYTES)
        val lineup =
            before()
                .withChannel("bravo") { it.copy(state = ChannelState.UserHidden, chosenByViewer = true) }
                .withChannel("charlie") {
                    it.copy(
                        state = ChannelState.UserHidden,
                        chosenByViewer = true,
                        source = it.starter.copy(rules = oversized.copy(timing = it.starter.rules.timing)),
                    )
                }.withChannel("alpha") {
                    it.copy(source = it.starter.copy(rules = oversized.copy(timing = it.starter.rules.timing)))
                }

        val next = swap(lineup)

        assertEquals(lineup.channel("bravo").starter.rules, next.channel("bravo").starter.rulesFor(noon))
        assertEquals(lineup.channel("delta").starter.rules, next.channel("delta").starter.rulesFor(noon))
        for (key in listOf("charlie", "alpha")) {
            val source = next.channel(key).starter
            assertNull(source.previousRules, key)
            assertTrue(Source.Genre("$key v3") in source.rules.sources, "$key takes the new rules at once")
        }
        assertEquals(ChannelState.On, next.channel("alpha").state)
    }

    // PR 5 final round, item 4: the budget is measured on the larger of the two copies a keeper can end up holding.
    // A starter whose current rules fit but whose pending previous rules do not keeps no copy either: it takes the new
    // rules at once and drops the oversized one, so the file bound holds.
    @Test
    fun aStarterWhosePreviousRulesAreOverThePerCopyBudgetKeepsNoCopy() {
        val oversized = rules((0 until 200).map { Source.Tag("an over-long tag of a much older rules file $it") })
        assertTrue(RecipeResolver.fileBytes(oversized) > RecipeResolver.STARTER_RULES_BUDGET_BYTES)
        val lineup =
            before().withChannel("alpha") {
                val previous = oversized.copy(timing = it.starter.rules.timing)
                it.copy(source = it.starter.withPrevious(previous, boundary))
            }
        val ownBytes = RecipeResolver.fileBytes(lineup.channel("alpha").starter.rules)
        assertTrue(ownBytes <= RecipeResolver.STARTER_RULES_BUDGET_BYTES)

        val next = swap(lineup)

        val alpha = next.channel("alpha").starter
        assertNull(alpha.previousRules)
        assertNull(alpha.rulesFromMs)
        assertTrue(Source.Genre("alpha v3") in alpha.rules.sources, "it takes the new rules at once")
        // A starter within the budget on both counts still keeps today's rules until the boundary.
        assertEquals(boundary, next.channel("bravo").starter.rulesFromMs)
    }

    // The opt-in starter's holdback keeps its copy before an ordinary hidden starter's, whatever their numbers. 120
    // starters leave room for 50 keepers: the 48 on the air and two more, which go to the opt-in one and the first
    // hidden one by number; the second hidden one takes the new rules at once.
    @Test
    fun theOptInStarterKeepsItsCopyBeforeHiddenOnes() {
        val keysOf = (0 until 100).map { "r$it" to 200 + it } + (0 until 19).map { "g$it" to 300 + it }
        val older = TestCatalog(keysOf.map { (k, n) -> recipeOf(k, n) } + recipeOf("late", 520, optIn = true), 2)
        val library = libraryOf(keysOf.associate { it.first to QUALIFYING_ITEMS } + ("late" to QUALIFYING_ITEMS))
        val chosen = planOf(null, library, older)
        assertEquals(120, chosen.channels.count { it.source is ChannelSource.Starter })
        val shelved = chosen.channels.filter { it.state == ChannelState.Shelf && it.id != LineupIds.starter("late") }
        val (first, second) = shelved.sortedBy { it.number }.take(2).map { it.id.value.substringAfter(':') }
        val lineup =
            chosen
                .withChannel(first) { it.copy(state = ChannelState.UserHidden, chosenByViewer = true) }
                .withChannel(second) { it.copy(state = ChannelState.UserHidden, chosenByViewer = true) }
        val newer = TestCatalog(older.recipes.map { it.copy(sources = it.sources + Source.Genre("new")) }, 3)

        val next = swap(lineup, library, newer)

        assertEquals(lineup.channel("late").starter.rules, next.channel("late").starter.rulesFor(noon), "opt-in kept")
        assertEquals(lineup.channel(first).starter.rules, next.channel(first).starter.rulesFor(noon), first)
        assertNull(next.channel(second).starter.previousRules, "$second is beyond the room")
    }

    // A hidden opt-in starter follows its recipe on a synced plan, from the boundary (spec 23, D1): what the leftovers
    // withhold for it reaches a collection that appears later, and not before the next day. A hidden starter that is
    // not opt-in is left as it is.
    @Test
    fun aHiddenOptInStarterFollowsItsRecipeFromTheBoundary() {
        val boxId = idOf(0x7000)
        val hinted =
            TestCatalog(
                plain.map {
                    if (it.key == "late" || it.key == "echo") it.copy(collectionHints = listOf("Box Set 1")) else it
                },
                version = 2,
            )
        val lineup =
            planOf(null, libraryOf(fills), hinted)
                .withChannel("late") { it.copy(state = ChannelState.UserHidden, chosenByViewer = true) }
                .withChannel("echo") { it.copy(state = ChannelState.UserHidden, chosenByViewer = true) }
        val box = collection(boxId, name = "Box Set 1")
        val names =
            tv.stationbreak.core.model
                .CollectionNames(mapOf(boxId to "Box Set 1"))

        val next =
            planOf(lineup, libraryOf(fills, collections = listOf(box)), hinted, nowMs = noon, names = names)

        val late = next.channel("late").starter
        assertEquals(lineup.channel("late").starter.rules, late.rulesFor(noon), "today's withheld set stays")
        assertTrue(Source.Collection(boxId) in late.rules.sources, "from the boundary it reaches the box set")
        assertEquals(boundary, late.rulesFromMs)
        assertEquals(lineup.channel("echo").starter, next.channel("echo").starter, "a hidden starter is left alone")
    }

    private fun leftoversOf(lineup: Lineup) = lineup.channels.filter { it.source is ChannelSource.Leftovers }

    private companion object {
        // Catalog 2 as it shipped, for a lineup chosen before the swap.
        val real2: StarterCatalogView =
            TestCatalog(CATALOG_V2_RECIPES, version = 2, leftovers = StarterCatalog.leftovers)

        // Thirty half-hour films of every TMDB genre, rated in turn G, PG, R, TV-Y and TV-14, and thirty animated
        // family films rated G: enough for most channels of either catalog to fill. Invented IDs only.
        val realLibrary: RuleSnapshot by lazy {
            val ratings = listOf("G", "PG", "R", "TV-Y", "TV-14")
            var next = 0x20000
            val items = ArrayList<RuleItem>()
            for (genre in TMDB_GENRES) {
                repeat(30) { items += movie(idOf(next++), rating = ratings[it % ratings.size], genres = listOf(genre)) }
            }
            repeat(30) { items += movie(idOf(next++), rating = "G", genres = listOf("Animation", "Family")) }
            snapshot(items = items)
        }
    }
}
