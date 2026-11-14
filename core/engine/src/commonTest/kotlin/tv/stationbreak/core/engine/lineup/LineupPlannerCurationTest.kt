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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** With a themed catalog the planner selects by identity, and shelves untouched starters at the boundary (spec 23). */
class LineupPlannerCurationTest {
    private val noon = 12 * 3_600_000L
    private val boundary = nextDayStart(noon)

    private fun selecting(
        recipes: List<Recipe>,
        version: Int = 1,
    ): TestCatalog = TestCatalog(recipes, version, selectsByIdentity = true)

    private fun themed(recipe: Recipe): Recipe =
        recipe.copy(ordering = OrderingMode.Curated, theme = Theme(emptyList(), emptyList(), null, false))

    private val five = listOf("alpha" to 110, "bravo" to 120, "charlie" to 210, "delta" to 310, "echo" to 410)

    // "dup" is about the same genre as "alpha" with a higher number: every title homes on "alpha".
    private val baseRecipes =
        five.map { (k, n) -> recipeOf(k, n) } + recipeOf("tots", 10) + recipeOf("dup", 150, sources = genre("alpha"))
    private val catalog = selecting(baseRecipes.map(::themed))
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

    // On the air from the boundary: switched on by the planner to start then.
    private fun LineupChannel.startsAtTheBoundary(): Boolean = state == ChannelState.On && onSinceMs == boundary

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

    private fun LineupChannel.chosenShelf(reason: ShelfReason?): LineupChannel =
        copy(state = ChannelState.Shelf, chosenByViewer = true, shelfReason = reason)

    private fun Lineup.withCustoms(count: Int): Lineup = copy(channels = channels + (0 until count).map(::custom))

    // An untouched starter on the air and not leaving.
    private fun Lineup.staying(key: String): Boolean {
        val channel = channel(key)
        return channel.state == ChannelState.On && channel.leavesAtMs == null
    }

    private fun Lineup.stayingOf(vararg keys: String): List<String> = keys.filter { staying(it) }

    private fun genre(vararg names: String): List<Source> = names.map { Source.Genre(it) }

    // ADR-0026, never lower a file that says 4: a themed refresh that picks a shelved studio starter stored at 4 keeps
    // it at 4 though the gate is off; one stored at 3 stays at 3.
    @Test
    fun aPickedStudioStarterThatSays4StaysAt4() {
        val foxtrot = recipeOf("foxtrot", 130, sources = genre("foxtrot") + Source.Studio("Studio A"))
        val withFoxtrot = selecting(catalog.recipes + themed(foxtrot))
        for (stored in listOf(3, 4)) {
            val lineup =
                first(libraryOf(fills()), withFoxtrot).withChannel("foxtrot") {
                    it.copy(source = it.starter.copy(rules = it.starter.rules.copy(formatVersion = stored)))
                }
            assertEquals(ChannelState.Shelf, lineup.channel("foxtrot").state)
            val next = replan(lineup, libraryOf(fills("foxtrot" to QUALIFYING_ITEMS)), withFoxtrot)
            val picked = next.channel("foxtrot")
            assertTrue(picked.startsAtTheBoundary())
            assertEquals(stored, picked.starter.rules.formatVersion, "stored at $stored")
        }
    }

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

    // A themed refresh switches nothing on in the middle of a day (D1, Task 21 fix round 2): the starter it picks is
    // on from the boundary (`onSinceMs`), as one it leaves out leaves at it. Today's on-set goes into the basis.
    @Test
    fun anUntouchedShelvedStarterThatNowQualifiesStartsAtTheBoundary() {
        val withFoxtrot = selecting(catalog.recipes + themed(recipeOf("foxtrot", 130)))
        val lineup = first(libraryOf(fills()), withFoxtrot)
        assertEquals(ChannelState.Shelf, lineup.channel("foxtrot").state)
        assertEquals(ShelfReason.Empty, lineup.channel("foxtrot").shelfReason)
        val library = libraryOf(fills("foxtrot" to QUALIFYING_ITEMS))

        val next = replan(lineup, library, withFoxtrot)

        val foxtrot = next.channel("foxtrot")
        assertTrue(foxtrot.startsAtTheBoundary(), "on from the boundary, not now")
        assertNull(foxtrot.shelfReason)
        assertNull(foxtrot.leavesAtMs)
        assertNull(foxtrot.starter.previousRules, "nothing of it aired before, so its rules apply at once")
        val basis = assertNotNull(next.curationBasis, "today's on-set is kept for today")
        assertEquals(boundary, basis.fromMs)
        assertFalse(LineupIds.starter("foxtrot") in basis.on)
        // A later plan the same day keeps it starting at the boundary; one that no longer picks it shelves it.
        assertTrue(replan(next, library, withFoxtrot, nowMs = noon + 1_000L).channel("foxtrot").startsAtTheBoundary())
        val dropped = replan(next, libraryOf(fills()), withFoxtrot, nowMs = noon + 2_000L).channel("foxtrot")
        assertEquals(ChannelState.Shelf, dropped.state, "it never aired, so it goes back on the shelf")
        assertNull(dropped.leavesAtMs)
        // A viewer switch-off before it starts shelves it too.
        val switchOff = LineupEdit.SwitchOff(LineupIds.starter("foxtrot"))
        val off = LineupEdits.apply(next, switchOff, noon, boundary, withFoxtrot)
        assertEquals(ChannelState.Shelf, assertIs<EditOutcome.Applied>(off).lineup.channel("foxtrot").state)
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

    // A lineup read from a file before version 3 cannot tell the viewer's switch-ons from the planner's: before its
    // first selection the planner infers them (A7). Delta, empty now, is outside the top the ranking would pick, so it
    // reads as the viewer's and stays; a shelved one still switches on, from the boundary.
    @Test
    fun anUpgradedLineupHasItsChoicesInferredBeforeItsFirstSelection() {
        val withFoxtrot = selecting(catalog.recipes + themed(recipeOf("foxtrot", 130)))
        val lineup = first(libraryOf(fills()), withFoxtrot).copy(viewerChoicesKnown = false)

        val library = libraryOf(fills("delta" to 0, "foxtrot" to QUALIFYING_ITEMS))
        val next = replan(lineup, library, withFoxtrot)

        assertTrue(next.viewerChoicesKnown)
        assertTrue(next.channel("delta").chosenByViewer)
        assertTrue(next.staying("delta"))
        assertFalse(next.channel("alpha").chosenByViewer)
        assertTrue(next.channel("foxtrot").startsAtTheBoundary())
    }

    @Test
    fun shelvedStartersCarryTheirReason() {
        val late = themed(recipeOf("late", 520, optIn = true))
        val withMore = selecting(catalog.recipes + themed(recipeOf("foxtrot", 130)) + late)
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
        val withLate = selecting(catalog.recipes + late + latest)
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
        // 60 starters, every one switched on by the viewer: more than the cap are on.
        val keys = List(60) { "r" + it.toString().padStart(2, '0') }
        val v1 = selecting(keys.mapIndexed { i, k -> themed(recipeOf(k, 200 + i)) })
        val library = libraryOf(keys.associateWith { QUALIFYING_ITEMS })
        val chosen = first(library, v1)
        val lineup =
            chosen.copy(
                channels =
                    chosen.channels.map {
                        val starter = it.source is ChannelSource.Starter
                        if (starter) it.copy(state = ChannelState.On, chosenByViewer = true) else it
                    },
            )

        fun version(n: Int) =
            selecting(
                keys.mapIndexed { i, k -> themed(recipeOf(k, 200 + i, sources = genre(k, "v$n"))) },
                version = n,
            )
        val next = replan(lineup, library, version(2))
        val again = replan(next, library, version(3), nowMs = noon + 1_000L)

        for ((n, planned) in listOf(2 to next, 3 to again)) {
            val on = planned.channels.filter { it.state == ChannelState.On && it.source is ChannelSource.Starter }
            assertEquals(60, on.size)
            val copies = planned.channels.count { (it.source as? ChannelSource.Starter)?.previousRules != null }
            assertTrue(copies <= LineupDefaults.CAP, "$copies previous copies")
            // The keepers keep today's rules; a starter beyond the cap holds no copy and takes the new rules at once
            // (the documented edge of more than the cap on).
            for (key in keys.take(10)) {
                assertEquals(lineup.channel(key).starter.rules, planned.channel(key).starter.rulesFor(noon), key)
            }
            for (key in keys.takeLast(10)) {
                assertTrue(
                    Source.Genre("v$n") in
                        planned
                            .channel(key)
                            .starter
                            .rulesFor(noon)
                            .sources,
                    key,
                )
                assertNull(planned.channel(key).starter.previousRules, key)
            }
        }
        val later = again.rolled(boundary)
        assertTrue(
            later
                .channel(keys.last())
                .starter.rules.sources
                .contains(Source.Genre("v3")),
        )
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
    private val withHouse = selecting(catalog.recipes + house)

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

    // A shelved starter the selection picks starts at the boundary, so only the boundary's flag counts.
    @Test
    fun aShelvedStarterStartsAtTheBoundaryUnderTheBoundarysFlag() {
        val shelved = fromNothing(true).withChannel("house") { it.copy(state = ChannelState.Shelf) }

        fun refreshed(
            flag: Boolean,
            basis: Boolean?,
        ): LineupChannel {
            val held = basis?.let { CurationBasis(boundary, emptySet(), it) }
            return householdPlan(shelved.copy(useMyCollections = flag, curationBasis = held)).channel("house")
        }
        assertTrue(refreshed(flag = true, basis = null).startsAtTheBoundary())
        // Off from tomorrow.
        assertEquals(ChannelState.Shelf, refreshed(flag = false, basis = true).state)
        // On from tomorrow, though off today: it starts at the boundary, when the flag is on.
        assertTrue(refreshed(flag = true, basis = false).startsAtTheBoundary())
    }

    // A viewer's renumber of a starter that is leaving keeps it: the edit clears the leaving time, and the planner pins
    // a chosen starter on the air even if a leaving time is still set.
    @Test
    fun aRenumberedLeavingStarterStays() {
        val emptied = libraryOf(fills("delta" to 0))
        val leaving = replan(first(), emptied)
        assertEquals(boundary, leaving.channel("delta").leavesAtMs)

        val edit = LineupEdit.SetNumber(LineupIds.starter("delta"), 311)
        val renumbered = assertIs<EditOutcome.Applied>(LineupEdits.apply(leaving, edit, noon, boundary, catalog)).lineup
        assertNull(renumbered.channel("delta").leavesAtMs)
        assertNull(renumbered.channel("delta").shelfReason)
        val next = replan(renumbered, emptied, nowMs = noon + 1_000L)
        assertTrue(next.staying("delta"))
        assertNull(next.channel("delta").shelfReason)

        // Chosen but still marked as leaving (written before the edit cleared it): the planner clears it.
        val stale = leaving.withChannel("delta") { it.copy(chosenByViewer = true) }
        val pinned = replan(stale, emptied, nowMs = noon + 1_000L).channel("delta")
        assertEquals(ChannelState.On, pinned.state)
        assertNull(pinned.leavesAtMs)
        assertNull(pinned.shelfReason)
    }

    @Test
    fun everyShelvedStarterHasACurrentReason() {
        val late = themed(recipeOf("late", 520, optIn = true))
        val golf = themed(recipeOf("golf", 140))
        val more = selecting(catalog.recipes + themed(recipeOf("foxtrot", 130)) + golf + late)
        val library = libraryOf(fills("late" to QUALIFYING_ITEMS, "golf" to QUALIFYING_ITEMS))
        val lineup =
            first(library, more)
                // The viewer gave alpha golf's number, so golf waits on the shelf with none.
                .withChannel("golf") { it.copy(state = ChannelState.Shelf, defaultNumber = null, shelfReason = null) }
                .withChannel("alpha") { it.copy(numberOverride = 140, chosenByViewer = true) }
                // Shelved by the viewer's choice, each with a stale reason or none.
                .withChannel("echo") { it.chosenShelf(ShelfReason.Empty) }
                .withChannel("dup") { it.copy(chosenByViewer = true, shelfReason = null) }
                .withChannel("foxtrot") { it.copy(chosenByViewer = true, shelfReason = ShelfReason.BelowCap) }

        val next = replan(lineup, library, more)

        val shelved = next.channels.filter { it.state == ChannelState.Shelf && it.source is ChannelSource.Starter }
        for (channel in shelved) assertNotNull(channel.shelfReason, channel.id.value)
        assertEquals(ShelfReason.BelowCap, next.channel("golf").shelfReason, "no number, so no room to air")
        assertEquals(ShelfReason.BelowCap, next.channel("echo").shelfReason, "it would qualify")
        assertEquals(ShelfReason.TooThin, next.channel("dup").shelfReason)
        assertEquals(ShelfReason.Empty, next.channel("foxtrot").shelfReason)
        assertEquals(ShelfReason.OptIn, next.channel("late").shelfReason)
        assertEquals(ChannelState.Shelf, next.channel("echo").state, "the viewer's shelf choice holds")
    }

    // PR 5 final round, item 3: an opt-in starter the viewer touched (switched off, or renumbered) and that waits on
    // the shelf is still off for being opt-in: it shows that reason, never one from the homing (it would qualify here).
    @Test
    fun aShelvedOptInStarterTheViewerTouchedShowsOptIn() {
        val late = themed(recipeOf("late", 520, optIn = true))
        val withLate = selecting(catalog.recipes + late)
        val library = libraryOf(fills("late" to QUALIFYING_ITEMS))
        val lineup = first(library, withLate)
        val touched = lineup.withChannel("late") { it.chosenShelf(null) }
        val renumbered = lineup.withChannel("late") { it.copy(numberOverride = 525, chosenByViewer = true) }

        assertEquals(ShelfReason.OptIn, replan(touched, library, withLate).channel("late").shelfReason)
        assertEquals(ShelfReason.OptIn, replan(renumbered, library, withLate).channel("late").shelfReason)
    }

    // PR 5 final round, item 8: the kept starters' reasons are worked out by a homing; with a memo and the snapshot's
    // generation, a plan whose inputs to them are those of the plan before reuses its reasons and skips the homing.
    @Test
    fun theKeptReasonsAreReusedWhileNothingTheyReadChanges() {
        val library = libraryOf(fills())
        val lineup =
            first(library)
                .withChannel("echo") { it.chosenShelf(ShelfReason.Empty) }
                .withChannel("dup") { it.chosenShelf(null) }
        val memo = KeptReasonsMemo()

        fun plan(
            current: Lineup,
            generation: Long?,
            m: KeptReasonsMemo? = memo,
            snapshot: RuleSnapshot = library,
        ): Lineup =
            LineupPlanner.plan(
                current,
                SERVER,
                USER,
                0,
                snapshot,
                NO_NAMES,
                noon,
                boundary,
                catalog,
                snapshotGeneration = generation,
                keptReasons = m,
            )

        val once = plan(lineup, 1L)
        assertEquals(1, memo.homings)
        val twice = plan(once, 1L)
        assertEquals(1, memo.homings, "nothing they read changed: no homing")
        assertEquals(once, twice)
        // The same reasons as a plan with no memo.
        assertEquals(plan(lineup, null, null), once)
        assertEquals(ShelfReason.BelowCap, once.channel("echo").shelfReason)
        assertEquals(ShelfReason.TooThin, once.channel("dup").shelfReason)

        // A new generation (the library changed) homes again, and so does a change of a kept starter.
        val grown = libraryOf(fills("dup" to QUALIFYING_ITEMS))
        val again = plan(twice, 2L, snapshot = grown)
        assertEquals(2, memo.homings)
        assertEquals(plan(twice, null, null, grown), again)
        plan(again.withChannel("bravo") { it.chosenShelf(null) }, 2L, snapshot = grown)
        assertEquals(3, memo.homings, "another kept starter")

        // Other libraries with the same generation (another choice of libraries) home again too.
        plan(again, 2L, snapshot = grown)
        assertEquals(4, memo.homings, "back to the lineup before")
        val otherLibraries = grown.copy(libraries = grown.libraries + idOf(0x7777))
        plan(again, 2L, snapshot = otherLibraries)
        assertEquals(5, memo.homings, "the libraries are part of the inputs")
        plan(again, 2L, snapshot = otherLibraries)
        assertEquals(5, memo.homings)

        // No kept starter: no homing at all.
        val none = KeptReasonsMemo()
        plan(first(library), 1L, none)
        assertEquals(0, none.homings)
    }

    // While a newer catalog waits for a collection pass over a lineup already selected, nothing switches on or off.
    @Test
    fun aWaitingCatalogHoldsTheOnSet() {
        val v3 = selecting(catalog.recipes, version = 3)
        val v4 = selecting(catalog.recipes + themed(recipeOf("foxtrot", 130)), version = 4)
        val lineup = first(libraryOf(fills()), v3).withChannel("delta") { it.copy(state = ChannelState.Shelf) }
        // An incremental sync: charlie is now empty, delta fills.
        val incremental = libraryOf(fills("charlie" to 0), collectionsSynced = false)

        val held = replan(lineup, incremental, v4)

        assertEquals(lineup.onKeys(), held.onKeys())
        assertTrue(held.channels.all { it.leavesAtMs == null })
        assertEquals(3, held.catalogVersion)
        // On its own catalog the same refresh selects: charlie leaves at the boundary, delta comes on then.
        val selected = replan(lineup, incremental, v3)
        assertEquals(boundary, selected.channel("charlie").leavesAtMs)
        assertTrue(selected.channel("delta").startsAtTheBoundary())
    }

    @Test
    fun noRoomShelvesEveryUntouchedStarterAtTheBoundary() {
        for (customs in listOf(LineupDefaults.CAP - 2, LineupDefaults.CAP + 5)) {
            val next = replan(first().withCustoms(customs), libraryOf(fills()))
            for (key in listOf("tots", "alpha", "bravo", "charlie", "delta", "echo")) {
                assertEquals(boundary, next.channel(key).leavesAtMs, "$customs: $key")
                assertEquals(ShelfReason.BelowCap, next.channel(key).shelfReason, "$customs: $key")
            }
        }
    }

    @Test
    fun withEveryStarterOnPinnedNothingLeaves() {
        val lineup =
            first().copy(
                channels =
                    first().channels.map {
                        val starterOn = it.source is ChannelSource.Starter && it.state == ChannelState.On
                        if (starterOn) it.copy(chosenByViewer = true) else it
                    },
            )

        val next = replan(lineup, libraryOf(fills("delta" to 0, "tots" to 0)))

        assertTrue(next.channels.all { it.leavesAtMs == null })
        assertEquals(lineup.onKeys(), next.onKeys())
        assertTrue(next.staying("tots"), "a pinned kid starter stays too")
    }

    @Test
    fun anUntouchedKidStarterThatEmptiesLeaves() {
        val next = replan(first(), libraryOf(fills("tots" to 0)))
        assertEquals(boundary, next.channel("tots").leavesAtMs)
        assertEquals(ShelfReason.Empty, next.channel("tots").shelfReason)
    }

    @Test
    fun aQuietRefreshChangesNothing() {
        val library = libraryOf(fills())
        val lineup = first(library)
        val once = replan(lineup, library)
        val twice = replan(once, library, nowMs = noon + 1_000L)
        assertEquals(lineup, once)
        assertEquals(once, twice)
    }
}
