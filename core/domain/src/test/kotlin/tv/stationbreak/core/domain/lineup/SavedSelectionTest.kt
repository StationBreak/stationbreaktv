// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.model.ItemId
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Spec 26, round 2 (arch LOW-1): the playable libraries are never more than the chosen ones. */
class SavedSelectionTest {
    private val a = ItemId("a".repeat(32))
    private val b = ItemId("b".repeat(32))

    @Test
    fun playableWithinTheChosen_orGone_isAccepted() {
        SavedSelection(setOf(a, b), seq = 1, playable = setOf(a))
        SavedSelection(setOf(a, b), seq = 1, playable = setOf(a, b))
        SavedSelection(setOf(a), seq = 1, playable = emptySet())
        SavedSelection(setOf(a), seq = 1, playable = null)
    }

    @Test
    fun aPlayableLibraryThatIsNotChosen_isRefused() {
        assertFailsWith<IllegalArgumentException> { SavedSelection(setOf(a), seq = 1, playable = setOf(a, b)) }
        assertFailsWith<IllegalArgumentException> { SavedSelection(emptySet(), seq = 0, playable = setOf(b)) }
    }
}
