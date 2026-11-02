// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** The ordering modes live in core:model so the rules format and the scheduler share one enum. */
class OrderingModeTest {
    @Test
    fun wireNamesAreUniqueAndExact() {
        val names = OrderingMode.entries.map { it.wireName }
        assertEquals(listOf("shuffle", "chronological", "randomInOrder", "rotation", "inOrder"), names)
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun theEntriesKeepTheirDeclaredOrder() {
        assertEquals(
            listOf(
                OrderingMode.Shuffle,
                OrderingMode.Chronological,
                OrderingMode.RandomInOrder,
                OrderingMode.Rotation,
                OrderingMode.InOrder,
            ),
            OrderingMode.entries,
        )
    }
}
