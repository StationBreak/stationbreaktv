// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.model.ItemId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
        assertEquals(setOf(a), SavedSelection(setOf(a, b), seq = 1, playable = setOf(a), resetSeq = 0).playable)
        assertEquals(setOf(a, b), SavedSelection(setOf(a, b), seq = 1, playable = setOf(a, b), resetSeq = 0).playable)
        assertEquals(emptySet(), SavedSelection(setOf(a), seq = 1, playable = emptySet(), resetSeq = 0).playable)
        assertNull(SavedSelection(setOf(a), seq = 1, playable = null, resetSeq = 0).playable)
    }

    @Test
    fun aPlayableLibraryThatIsNotChosen_isDropped() {
        assertEquals(setOf(a), SavedSelection(setOf(a), seq = 1, playable = setOf(a, b), resetSeq = 0).playable)
        assertEquals(emptySet(), SavedSelection(emptySet(), seq = 0, playable = setOf(b), resetSeq = 0).playable)
        val cut = SavedSelection(setOf(a), 1, setOf(a, b), 0)
        assertEquals(SavedSelection(setOf(a), 1, setOf(a), 0), cut, "equal once cut")
    }

    @Test
    fun theResetSeq_isPartOfTheValue() {
        val reset = SavedSelection(setOf(a), seq = 3, playable = setOf(a), resetSeq = 3)
        assertEquals(3L, reset.resetSeq)
        assertNotEquals(SavedSelection(setOf(a), seq = 3, playable = setOf(a), resetSeq = 0), reset)
        assertEquals(SavedSelection(setOf(a), seq = 3, playable = setOf(a), resetSeq = 3).hashCode(), reset.hashCode())
    }
}
