// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.schedule

import tv.stationbreak.core.model.CanonicalId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TitleSideInputsTest {
    private val a = CanonicalId.parse("a".repeat(32))
    private val b = CanonicalId.parse("b".repeat(32))

    @Test
    fun anAbsentTitleHasWeightOne() {
        assertEquals(1, TitleWeights.NONE[a])
        assertEquals(3, TitleWeights.of(mapOf(a to 3))[a])
        assertEquals(1, TitleWeights.of(mapOf(a to 3))[b])
    }

    @Test
    fun weightsAreOneToThree() {
        assertFailsWith<IllegalArgumentException> { TitleWeights.of(mapOf(a to 0)) }
        assertFailsWith<IllegalArgumentException> { TitleWeights.of(mapOf(a to 4)) }
    }

    @Test
    fun entriesAreSortedByIdWhateverTheInsertionOrder() {
        assertEquals(listOf(a to 2, b to 3), TitleWeights.of(linkedMapOf(b to 3, a to 2)).entries)
        assertEquals(listOf(a to 5L, b to 9L), TitleKeeps.of(linkedMapOf(b to 9L, a to 5L)).entries)
    }

    @Test
    fun aKeepIsAtLeastOneMillisecond() {
        assertNull(TitleKeeps.NONE[a])
        assertEquals(1L, TitleKeeps.of(mapOf(a to 1L))[a])
        assertFailsWith<IllegalArgumentException> { TitleKeeps.of(mapOf(a to 0L)) }
    }
}
