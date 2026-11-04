// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.collection
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.rules
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** With a themed catalog the planner selects by identity, and shelves untouched starters at the boundary (spec 23). */
class LineupPlannerCurationTest {
    private val noon = 12 * 3_600_000L
    private val boundary = nextDayStart(noon)

    private fun themed(recipe: Recipe): Recipe =
        recipe.copy(ordering = OrderingMode.Curated, theme = Theme(emptyList(), emptyList(), null, false))

    private val five = listOf("alpha" to 110, "bravo" to 120, "charlie" to 210, "delta" to 310, "echo" to 410)

    // "dup" is about the same genre as "alpha" with a higher number: every title homes on "alpha".
    private val baseRecipes =
        five.map { (k, n) -> recipeOf(k, n) } + recipeOf("tots", 10) + recipeOf("dup", 150, sources = genre("alpha"))
    private val catalog = TestCatalog(baseRecipes.map(::themed))
    private val unthemed = TestCatalog(baseRecipes)

    private fun fills(vararg extra: Pair<String, Int>): Map<String, Int> =
        (five.map { it.first } + "tots").associateWith { QUALIFYING_ITEMS } + extra.toMap()

    private fun first(
        library: RuleSnapshot = libraryOf(fills()),
        c: StarterCatalogView = catalog,
    ): Lineup = planOf(null, library, c).also { assertTrue(it.finalised) }

    private fun replan(
        lineup: Lineup,
        library: RuleSnapshot,
        c: StarterCatalogView = catalog,
        nowMs: Long = noon,
        boundaryMs: Long = boundary,
    ): Lineup = planOf(lineup, library, c, nowMs = nowMs, boundaryMs = boundaryMs)

    private fun firstAround(
        kept: List<LineupChannel>,
        library: RuleSnapshot,
        c: StarterCatalogView,
    ): Lineup {
        val boundaryMs = nextDayStart(1_000L)
        return LineupPlanner.plan(null, SERVER, USER, 0, library, NO_NAMES, 1_000L, boundaryMs, c, kept = kept)
    }

    private fun custom(n: Int): LineupChannel =
        LineupChannel(
            ChannelId("custom:$n"),
            defaultNumber = null,
            numberOverride = 600 + n,
            nameOverride = null,
            state = ChannelState.On,
            source = ChannelSource.Custom(rules(listOf(Source.Genre("none")))),
            onSinceMs = 0L,
        )

    private fun Lineup.withCustoms(count: Int): Lineup = copy(channels = channels + (0 until count).map(::custom))

    // An untouched starter on the air and not leaving.
    private fun Lineup.staying(key: String): Boolean {
        val channel = channel(key)
        return channel.state == ChannelState.On && channel.leavesAtMs == null
    }

    private fun Lineup.stayingOf(vararg keys: String): List<String> = keys.filter { staying(it) }

    private fun genre(vararg names: String): List<Source> = names.map { Source.Genre(it) }

    @Test
    fun theFirstChoiceUsesSelection() {
        val library = libraryOf(fills())
        val selected = first(library)
        // "dup" fills on its own, but once every title has its best home it holds none.
        assertEquals(ChannelState.Shelf, selected.channel("dup").state)
        assertEquals(ShelfReason.TooThin, selected.channel("dup").shelfReason)
        val all = setOf("alpha", "bravo", "charlie", "delta", "echo", "tots", "kids-odds", "odds")
        assertEquals(all, selected.onKeys())
        // An unthemed catalog keeps the ranking: "dup" fills, so it is on, and nothing carries a reason.
        val ranked = first(library, unthemed)
        assertEquals(ChannelState.On, ranked.channel("dup").state)
        assertTrue(ranked.channels.all { it.shelfReason == null })
    }

    @Test
    fun anUntouchedShelvedStarterThatNowQualifiesSwitchesOn() {
        val withFoxtrot = TestCatalog(catalog.recipes + themed(recipeOf("foxtrot", 130)))
        val lineup = first(libraryOf(fills()), withFoxtrot)
        assertEquals(ChannelState.Shelf, lineup.channel("foxtrot").state)
        assertEquals(ShelfReason.Empty, lineup.channel("foxtrot").shelfReason)

        val next = replan(lineup, libraryOf(fills("foxtrot" to QUALIFYING_ITEMS)), withFoxtrot)

        val foxtrot = next.channel("foxtrot")
        assertEquals(ChannelState.On, foxtrot.state, "on now, in the middle of the day")
        assertEquals(noon, foxtrot.onSinceMs)
        assertNull(foxtrot.shelfReason)
        assertNull(foxtrot.leavesAtMs)
        assertNull(foxtrot.starter.previousRules, "nothing of it aired today, so its rules apply at once")
    }

    @Test
    fun anUntouchedOnStarterThatFailsLeavesAtTheNextBoundary() {
        val lineup = first()
        val emptied = libraryOf(fills("delta" to 0))

        val next = replan(lineup, emptied)

        val delta = next.channel("delta")
        assertEquals(ChannelState.On, delta.state, "it airs until the boundary")
        assertEquals(boundary, delta.leavesAtMs)
        assertEquals(ShelfReason.Empty, delta.shelfReason)
        assertEquals(ChannelState.Shelf, next.rolled(boundary).channel("delta").state)
        // A later plan keeps the boundary it was given first.
        val later = replan(next, emptied, nowMs = noon + 1_000L, boundaryMs = boundary + DAY_MS)
        assertEquals(boundary, later.channel("delta").leavesAtMs)
        // Once it fills again it stays.
        val back = replan(next, libraryOf(fills()), nowMs = noon + 2_000L)
        assertTrue(back.staying("delta"))
        assertNull(back.channel("delta").shelfReason)
    }

    @Test
    fun aViewerChosenStarterNeverLeaves() {
        val lineup = first().withChannel("delta") { it.copy(chosenByViewer = true) }

        val next = replan(lineup, libraryOf(fills("delta" to 0)))

        assertTrue(next.staying("delta"))
        assertNull(next.channel("delta").shelfReason)
    }

    // A lineup read from a file before version 3 cannot tell the viewer's switch-ons from the planner's, so any
    // starter on the air may be the viewer's: none is shelved. A shelved one still switches on.
    @Test
    fun anUpgradedLineupNeverShelvesAStarterOnTheAir() {
        val withFoxtrot = TestCatalog(catalog.recipes + themed(recipeOf("foxtrot", 130)))
        val lineup = first(libraryOf(fills()), withFoxtrot).copy(viewerChoicesKnown = false)

        val next = replan(lineup, libraryOf(fills("delta" to 0, "foxtrot" to QUALIFYING_ITEMS)), withFoxtrot)

        assertTrue(next.staying("delta"))
        assertEquals(ChannelState.On, next.channel("foxtrot").state)
        assertFalse(next.viewerChoicesKnown, "the planner does not decide that here")
    }

    @Test
    fun shelvedStartersCarryTheirReason() {
        val late = themed(recipeOf("late", 520, optIn = true))
        val withMore = TestCatalog(catalog.recipes + themed(recipeOf("foxtrot", 130)) + late)
        val library = libraryOf(fills("late" to QUALIFYING_ITEMS))
        // 44 of the viewer's own channels and the two leftovers leave room for four starters.
        val kept = (0 until 44).map(::custom)

        val lineup = firstAround(kept, library, withMore)

        val starterKeys = withMore.recipes.map { it.key }.toSet()
        assertEquals(setOf("tots", "alpha", "bravo", "charlie"), lineup.onKeys() intersect starterKeys)
        assertEquals(ShelfReason.BelowCap, lineup.channel("delta").shelfReason)
        assertEquals(ShelfReason.BelowCap, lineup.channel("echo").shelfReason)
        assertEquals(ShelfReason.TooThin, lineup.channel("dup").shelfReason)
        assertEquals(ShelfReason.Empty, lineup.channel("foxtrot").shelfReason)
        assertEquals(ShelfReason.OptIn, lineup.channel("late").shelfReason)
        for (channel in lineup.channels) {
            if (channel.state == ChannelState.On) assertNull(channel.shelfReason, channel.id.value)
            if (channel.state == ChannelState.Shelf) assertNotNull(channel.shelfReason, channel.id.value)
        }
        // A refresh keeps every shelved starter's reason.
        val next = replan(lineup, library, withMore)
        assertEquals(ShelfReason.OptIn, next.channel("late").shelfReason)
        assertEquals(ShelfReason.Empty, next.channel("foxtrot").shelfReason)
        assertEquals(ShelfReason.BelowCap, next.channel("delta").shelfReason)
    }

    @Test
    fun theCapCountsPinnedAndCustomChannels() {
        // 50 - 2 leftovers - 44 own channels - 1 pinned (alpha) leaves room for three untouched starters.
        val lineup = first().withCustoms(44).withChannel("alpha") { it.copy(chosenByViewer = true) }

        val next = replan(lineup, libraryOf(fills()))

        assertTrue(next.staying("alpha"))
        val staying = listOf("tots", "bravo", "charlie", "delta", "echo").filter { next.staying(it) }
        assertEquals(listOf("tots", "bravo", "charlie"), staying, "the room, ties to the lower number")
        for (key in listOf("delta", "echo")) {
            assertEquals(boundary, next.channel(key).leavesAtMs, key)
            assertEquals(ShelfReason.BelowCap, next.channel(key).shelfReason, key)
        }
    }

    @Test
    fun aPinnedOptInStarterIsACandidate() {
        val late = themed(recipeOf("late", 520, optIn = true))
        val latest = themed(recipeOf("latest", 530, sources = listOf(Source.Genre("late"))))
        val withLate = TestCatalog(catalog.recipes + late + latest)
        val library = libraryOf(fills("late" to QUALIFYING_ITEMS))
        val lineup = first(library, withLate)
        assertEquals(ShelfReason.OptIn, lineup.channel("late").shelfReason)
        assertTrue(lineup.staying("latest"))

        // The viewer switches the opt-in starter on: it takes its titles' homes, so "latest" holds none.
        val chosen =
            lineup.withChannel("late") { it.copy(state = ChannelState.On, chosenByViewer = true, shelfReason = null) }
        val next = replan(chosen, library, withLate)

        assertTrue(next.staying("late"))
        assertNull(next.channel("late").shelfReason)
        assertEquals(boundary, next.channel("latest").leavesAtMs)
        assertEquals(ShelfReason.TooThin, next.channel("latest").shelfReason)
    }

    @Test
    fun aLeavingChannelDoesNotTakeRoom() {
        // 50 - 2 leftovers - 45 own channels leaves room for three. "alpha" is leaving (and now empty); delta and
        // echo wait on the shelf.
        val lineup =
            first()
                .withCustoms(45)
                .withChannel("alpha") { it.copy(leavesAtMs = boundary, shelfReason = ShelfReason.Empty) }
                .withChannel("delta") { it.copy(state = ChannelState.Shelf) }
                .withChannel("echo") { it.copy(state = ChannelState.Shelf) }

        val next = replan(lineup, libraryOf(fills("alpha" to 0)))

        assertEquals(listOf("tots", "bravo", "charlie"), next.stayingOf("tots", "bravo", "charlie", "delta", "echo"))
        assertEquals(boundary, next.channel("alpha").leavesAtMs)
        assertEquals(ShelfReason.BelowCap, next.channel("delta").shelfReason)
    }

    @Test
    fun thePlannerSetsTheBasisWhenTheOnSetChanges() {
        val lineup = first()
        val before =
            lineup.channels
                .filter { it.state == ChannelState.On && it.source is ChannelSource.Starter }
                .map { it.id }
                .toSet()
        assertNull(replan(lineup, libraryOf(fills())).curationBasis, "nothing changed")

        val next = replan(lineup, libraryOf(fills("delta" to 0)))

        assertEquals(CurationBasis(boundary, before, true), next.curationBasis)
    }

    @Test
    fun thePreviousRulesStayWithinTheCap() {
        // 60 starters: the first choice switches on 48; the 12 left fill better later and switch on at once while 12
        // others leave at the boundary, so more than the cap are on until then.
        val keys = List(60) { "r" + it.toString().padStart(2, '0') }
        val v1 = TestCatalog(keys.mapIndexed { i, k -> themed(recipeOf(k, 200 + i)) })
        val lineup = first(libraryOf(keys.associateWith { QUALIFYING_ITEMS }), v1)
        val startersOn = lineup.channels.count { it.state == ChannelState.On && it.source is ChannelSource.Starter }
        assertEquals(LineupDefaults.CAP - 2, startersOn)
        val shelved = keys.filter { lineup.channel(it).state == ChannelState.Shelf }
        val richer = libraryOf(keys.associateWith { if (it in shelved) 30 else QUALIFYING_ITEMS })

        fun version(n: Int) =
            TestCatalog(
                keys.mapIndexed { i, k -> themed(recipeOf(k, 200 + i, sources = genre(k, "v$n"))) },
                version = n,
            )
        val next = replan(lineup, richer, version(2))
        val again = replan(next, richer, version(3), nowMs = noon + 1_000L)

        for (planned in listOf(next, again)) {
            val on = planned.channels.filter { it.state == ChannelState.On && it.source is ChannelSource.Starter }
            assertEquals(60, on.size, "the leaving ones are on until the boundary")
            val copies = planned.channels.count { (it.source as? ChannelSource.Starter)?.previousRules != null }
            assertTrue(copies <= LineupDefaults.CAP, "$copies previous copies")
        }
        for (key in shelved) assertNull(next.channel(key).starter.previousRules, key)
        assertEquals(12, next.channels.count { it.leavesAtMs == boundary })
    }

    // A recipe that fills only through a household collection ("Box Set 2", not a server's "... Collection").
    private val boxSet = idOf(0xB2)
    private val boxNames = CollectionNames(mapOf(boxSet to "Box Set 2"))
    private val boxSetAnchor = Anchor(AnchorKind.Collection, NameMatcher.Exact("Box Set 2"))
    private val house =
        recipeOf("house", 130).copy(
            ordering = OrderingMode.Curated,
            theme = Theme(listOf(boxSetAnchor), emptyList(), null, false),
        )
    private val withHouse = TestCatalog(catalog.recipes + house)

    private fun householdLibrary(): RuleSnapshot {
        val library = libraryOf(fills("members" to QUALIFYING_ITEMS))
        val members =
            library.items
                .filter { "members" in it.genres }
                .map { it.item.id }
                .toSet()
        return library.copy(collections = mapOf(boxSet to collection(boxSet, movies = members, name = "Box Set 2")))
    }

    private fun householdPlan(lineup: Lineup): Lineup =
        planOf(lineup, householdLibrary(), withHouse, nowMs = noon, names = boxNames, boundaryMs = boundary)

    private fun fromNothing(flag: Boolean): Lineup =
        LineupPlanner.plan(
            null,
            SERVER,
            USER,
            0,
            householdLibrary(),
            boxNames,
            1_000L,
            nextDayStart(1_000L),
            withHouse,
            useMyCollections = flag,
        )

    @Test
    fun householdOffChangesWhatThePlannerSelects() {
        assertEquals(ChannelState.On, fromNothing(true).channel("house").state)
        val off = fromNothing(false).channel("house")
        assertEquals(ChannelState.Shelf, off.state)
        assertEquals(ShelfReason.Empty, off.shelfReason, "with the household's collections off nothing fits it")

        // Switched off earlier today: it airs today and leaves at the boundary.
        val on = fromNothing(true)
        val switchedOff = on.copy(useMyCollections = false, curationBasis = CurationBasis(boundary, emptySet(), true))
        val leaving = householdPlan(switchedOff)
        assertEquals(boundary, leaving.channel("house").leavesAtMs)
        assertEquals(ShelfReason.Empty, leaving.channel("house").shelfReason)
    }

    // A shelved starter switched on now airs from now: it must be selected under today's flag and the boundary's.
    @Test
    fun aShelvedStarterSwitchesOnNowOnlyUnderBothFlags() {
        val shelved = fromNothing(true).withChannel("house") { it.copy(state = ChannelState.Shelf) }

        fun refreshed(
            flag: Boolean,
            basis: Boolean?,
        ): LineupChannel {
            val held = basis?.let { CurationBasis(boundary, emptySet(), it) }
            return householdPlan(shelved.copy(useMyCollections = flag, curationBasis = held)).channel("house")
        }
        assertEquals(ChannelState.On, refreshed(flag = true, basis = null).state)
        // Off from tomorrow.
        assertEquals(ChannelState.Shelf, refreshed(flag = false, basis = true).state)
        // On from tomorrow, but off today: it waits for the plan at the boundary.
        val waiting = refreshed(flag = true, basis = false)
        assertEquals(ChannelState.Shelf, waiting.state)
        assertEquals(ShelfReason.Empty, waiting.shelfReason)
    }
}
