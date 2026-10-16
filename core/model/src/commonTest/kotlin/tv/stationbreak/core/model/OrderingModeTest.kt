// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** The ordering modes live in core:model so the rules format and the scheduler share one enum. */
class OrderingModeTest {
    @Test
    fun theWireNamesAreTheFilesExactSpellings() {
        assertEquals(
            listOf("shuffle", "chronological", "randomInOrder"),
            OrderingMode.entries.map { it.wireName },
        )
    }

    @Test
    fun theEntriesKeepTheirDeclaredOrder() {
        assertEquals(
            listOf(OrderingMode.Shuffle, OrderingMode.Chronological, OrderingMode.RandomInOrder),
            OrderingMode.entries,
        )
    }
}
