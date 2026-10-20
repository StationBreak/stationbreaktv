// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.ChannelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LineupTest {
    private fun channel(
        id: String,
        number: Int?,
        state: ChannelState = ChannelState.On,
        override: Int? = null,
    ) = LineupChannel(ChannelId(id), number, override, null, state, ChannelSource.Leftovers("odds", null), 0L)

    private fun built(channels: List<LineupChannel>) = Lineup(SERVER, USER, 1, -300, true, channels)

    private fun checked(channels: List<LineupChannel>) = Lineup.ofOrNull(SERVER, USER, 1, -300, true, channels)

    @Test
    fun ofOrNullGivesTheLineupWhenItHoldsTogether() {
        val channels =
            listOf(
                channel("a", 1),
                channel("b", 2, override = 7),
                channel("c", null, ChannelState.Shelf),
                channel("d", null, ChannelState.UserHidden),
            )
        assertEquals(built(channels), checked(channels))
        assertEquals(built(emptyList()), checked(emptyList()))
    }

    @Test
    fun ofOrNullGivesNullWhereTheConstructorThrows() {
        val broken =
            mapOf(
                "duplicate IDs" to listOf(channel("a", 1), channel("a", 2)),
                "duplicate default numbers" to listOf(channel("a", 1), channel("b", 1, ChannelState.Shelf)),
                "an override on another number" to listOf(channel("a", 1), channel("b", 2, override = 1)),
                "a switched-on channel without a number" to listOf(channel("a", null)),
            )
        for ((what, channels) in broken) {
            assertNull(checked(channels), what)
            assertFailsWith<IllegalArgumentException>(what) { built(channels) }
        }
    }

    @Test
    fun aLeftoversCeilingIsOnTheLadder() {
        assertNull(ChannelSource.Leftovers("odds", null).maxRating)
        assertEquals("PG", ChannelSource.Leftovers("kids-odds", "PG").maxRating)
        assertFailsWith<IllegalArgumentException> { ChannelSource.Leftovers("kids-odds", "PG-14") }
        assertFailsWith<IllegalArgumentException> { ChannelSource.Leftovers("kids-odds", "pg") }
    }
}
