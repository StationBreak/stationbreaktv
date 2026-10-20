// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LineupTest {
    @Test
    fun aLeftoversCeilingIsOnTheLadder() {
        assertNull(ChannelSource.Leftovers("odds", null).maxRating)
        assertEquals("PG", ChannelSource.Leftovers("kids-odds", "PG").maxRating)
        assertFailsWith<IllegalArgumentException> { ChannelSource.Leftovers("kids-odds", "PG-14") }
        assertFailsWith<IllegalArgumentException> { ChannelSource.Leftovers("kids-odds", "pg") }
    }
}
