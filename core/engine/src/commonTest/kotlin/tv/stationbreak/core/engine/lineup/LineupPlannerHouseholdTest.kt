// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.collection
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.RuleSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** With "Use my collections" off the planner ranks starters on the pools they will air (spec 23). */
class LineupPlannerHouseholdTest {
    private val five = listOf("alpha" to 110, "bravo" to 120, "charlie" to 210, "delta" to 310, "echo" to 410)

    // A recipe that fills only through a household collection (not named "... Collection"): its genre holds no items.
    private val house = recipeOf("house", 130, hints = listOf("Box Set 2"))
    private val withHouse = TestCatalog(five.map { (k, n) -> recipeOf(k, n) } + recipeOf("tots", 10) + house)
    private val boxSet = idOf(0xB2)
    private val boxNames = CollectionNames(mapOf(boxSet to "Box Set 2"))

    private fun householdLibrary(): RuleSnapshot {
        val own = withHouse.recipes.associate { it.key to QUALIFYING_ITEMS }
        val fills = own + mapOf("members" to QUALIFYING_ITEMS, "house" to 0)
        val library = libraryOf(fills)
        val members =
            library.items
                .filter { "members" in it.genres }
                .map { it.item.id }
                .toSet()
        return library.copy(collections = mapOf(boxSet to collection(boxSet, movies = members, name = "Box Set 2")))
    }

    @Test
    fun householdOffChangesWhatThePlannerRanks() {
        val library = householdLibrary()

        fun ranked(household: Boolean): List<String> {
            val planning = Planning(SERVER, 0, library, boxNames, withHouse, 1_000L, nextDayStart(1_000L), household)
            return planning.ranked(withHouse.recipes.map { it to planning.resolve(it) }).map { it.key }
        }
        assertTrue("house" in ranked(true))
        assertFalse("house" in ranked(false), "its pool is only a household collection, so it ranks as empty")
        assertTrue(ranked(false).containsAll(five.map { it.first }))
    }

    @Test
    fun theLineupsFlagDecidesWhichShelvedStarterSwitchesOn() {
        val library = householdLibrary()
        val first = planOf(null, library, withHouse, names = boxNames)
        assertEquals(ChannelState.On, first.channel("house").state)
        val shelved = first.withChannel("house") { it.copy(state = ChannelState.Shelf) }
        val on = planOf(shelved, library, withHouse, names = boxNames)
        assertEquals(ChannelState.On, on.channel("house").state)
        val off = shelved.copy(useMyCollections = false)
        assertEquals(ChannelState.Shelf, planOf(off, library, withHouse, names = boxNames).channel("house").state)
    }

    // A shelved "house" with the flag and the basis given; refreshed at 14:00, so today began at 06:00.
    private fun refreshed(
        flag: Boolean,
        basisHousehold: Boolean?,
    ): Lineup {
        val library = householdLibrary()
        val shelved =
            planOf(null, library, withHouse, names = boxNames).withChannel("house") {
                it.copy(state = ChannelState.Shelf)
            }
        val nowMs = 14 * 3_600_000L
        val boundary = nextDayStart(nowMs)
        val basis = basisHousehold?.let { CurationBasis(boundary, emptySet(), it) }
        val lineup = shelved.copy(useMyCollections = flag, curationBasis = basis)
        return planOf(lineup, library, withHouse, nowMs = nowMs, names = boxNames, boundaryMs = boundary)
    }

    @Test
    fun aStarterIsSwitchedOnOnlyIfItFillsUnderTodaysFlagAndTomorrows() {
        assertEquals(ChannelState.On, refreshed(flag = true, basisHousehold = null).channel("house").state)
        // Switched off earlier today: today still has the household's collections, tomorrow does not.
        assertEquals(ChannelState.Shelf, refreshed(flag = false, basisHousehold = true).channel("house").state)
        // Switched back on today after having been off: tomorrow has them, today's day build does not.
        assertEquals(ChannelState.Shelf, refreshed(flag = true, basisHousehold = false).channel("house").state)
    }

    // A setting survives a plan: a lineup not yet finalised keeps its flag (and basis) through its first choice, and
    // ranks under it.
    @Test
    fun anUnfinalisedLineupKeepsItsFlagThroughTheFirstChoice() {
        val library = householdLibrary()
        val basis = CurationBasis(nextDayStart(1_000L), emptySet(), true)
        val unfinalised =
            Lineup(SERVER, USER, 1, 0, false, emptyList(), useMyCollections = false, curationBasis = basis)

        val chosen = planOf(unfinalised, library, withHouse, names = boxNames)

        assertTrue(chosen.finalised)
        assertFalse(chosen.useMyCollections)
        assertEquals(basis, chosen.curationBasis)
        assertEquals(ChannelState.Shelf, chosen.channel("house").state, "ranked with the household's collections off")
        // Still waiting for the collection pass: the flag stays all the same.
        val waiting = planOf(unfinalised, library.copy(collectionsSynced = false), withHouse, names = boxNames)
        assertFalse(waiting.finalised)
        assertFalse(waiting.useMyCollections)
    }

    // A reset plans from nothing and carries the flag of the lineup it replaces.
    @Test
    fun aLineupPlannedFromNothingTakesTheFlagItIsGiven() {
        val library = householdLibrary()

        fun fromNothing(flag: Boolean) =
            LineupPlanner.plan(
                null,
                SERVER,
                USER,
                0,
                library,
                boxNames,
                1_000L,
                nextDayStart(1_000L),
                withHouse,
                useMyCollections = flag,
            )
        val off = fromNothing(false)
        assertFalse(off.useMyCollections)
        assertEquals(ChannelState.Shelf, off.channel("house").state)
        val on = fromNothing(true)
        assertTrue(on.useMyCollections)
        assertEquals(ChannelState.On, on.channel("house").state)
    }
}
