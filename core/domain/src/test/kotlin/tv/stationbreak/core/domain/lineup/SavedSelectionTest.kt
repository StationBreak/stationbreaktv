// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.model.ItemId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Spec 26, rounds 2 and 3 (arch LOW-1): the playable libraries are never more than the chosen ones. The read is on the
 * tune path, so more is cut back to the chosen ones, never thrown on.
 */
class SavedSelectionTest {
    private val a = ItemId("a".repeat(32))
    private val b = ItemId("b".repeat(32))

    @Test
    fun playableWithinTheChosen_orGone_isKeptAsGiven() {
        assertEquals(setOf(a), SavedSelection(setOf(a, b), seq = 1, playable = setOf(a)).playable)
        assertEquals(setOf(a, b), SavedSelection(setOf(a, b), seq = 1, playable = setOf(a, b)).playable)
        assertEquals(emptySet(), SavedSelection(setOf(a), seq = 1, playable = emptySet()).playable)
        assertNull(SavedSelection(setOf(a), seq = 1, playable = null).playable)
    }

    @Test
    fun aPlayableLibraryThatIsNotChosen_isDropped() {
        assertEquals(setOf(a), SavedSelection(setOf(a), seq = 1, playable = setOf(a, b)).playable)
        assertEquals(emptySet(), SavedSelection(emptySet(), seq = 0, playable = setOf(b)).playable)
        assertEquals(SavedSelection(setOf(a), 1, setOf(a)), SavedSelection(setOf(a), 1, setOf(a, b)), "equal once cut")
    }
}
