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
}
