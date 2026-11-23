// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #1115 (L4): a catalog bump waits for a full collection pass, and `CatalogRenumbering` runs only then. Until it does,
 * the floor work must not touch a built-in whose recipe the new catalog moves up a band: a number it picks in the
 * meantime would stay as the viewer's own number and keep the channel off the catalog's number for good.
 */
class PendingRenumberingFloorsTest {
    private val others =
        listOf("alpha" to 110, "bravo" to 120, "charlie" to 210, "delta" to 310, "echo" to 410, "tots" to 10)
            .map { (key, number) -> recipeOf(key, number) }
    private val old = TestCatalog(others + recipeOf("mover", 40), version = StarterCatalog.YOUNG_BANDS_VERSION - 1)

    // The same recipe in the Teen band: its floor is now 67, and the catalog's number for it is 70.
    private val newer = TestCatalog(others + recipeOf("mover", 70), version = StarterCatalog.YOUNG_BANDS_VERSION)

    private fun fills(catalog: StarterCatalogView) = catalog.recipes.associate { it.key to QUALIFYING_ITEMS }

    private fun installed(): Lineup {
        val first = planOf(null, libraryOf(fills(old)), old)
        assertTrue(first.finalised)
        // Whatever the planner chose, the mover is installed at its default of the old catalog, wherever it is on.
        return first.withChannel("mover") { it.copy(defaultNumber = 40, numberOverride = null) }
    }

    private fun replan(
        lineup: Lineup,
        synced: Boolean,
    ) = planOf(lineup, libraryOf(fills(newer), collectionsSynced = synced), newer, nowMs = 9_000L)

    @Test
    fun aBuiltInWhoseRecipeMovesUpABandStaysPutUntilTheCollectionPassThenTakesTheCatalogNumber() {
        val before = installed()
        val waiting = replan(before, synced = false)
        assertEquals(before.catalogVersion, waiting.catalogVersion, "the catalog waits")
        assertEquals(before.channels, waiting.channels, "nothing moves while the catalog waits, the floors included")
        assertTrue(waiting.movedNotices.isEmpty(), "no notice for a move that did not happen")

        val synced = replan(waiting, synced = true)
        val mover = synced.channel("mover")
        assertEquals(70, mover.number, "the catalog's number, not one the floor work picked in the meantime")
        assertNull(mover.numberOverride)
        val numbers = synced.channels.mapNotNull { it.number }
        assertEquals(numbers.size, numbers.toSet().size, "no number twice")
        assertTrue(synced.movedNotices.none { it.id == mover.id }, "the viewer is not told of a move they never had")
    }
}
