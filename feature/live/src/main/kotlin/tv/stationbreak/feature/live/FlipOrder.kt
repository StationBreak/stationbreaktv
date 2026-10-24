// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.model.ChannelId

/** Which way a flip goes: Up is the next higher channel number, Down the next lower. */
enum class FlipDirection { Up, Down }

/**
 * The channels a flip visits, in number order: the unhidden [ChannelAiring]s of one snapshot. Hidden channels (empty
 * today, #299) are not in it, so a flip never lands on one.
 */
internal class FlipOrder(
    airings: List<ChannelAiring>,
) {
    private val channels: List<ChannelAiring> = airings.filter { it.hidden == null }.sortedBy { it.number }

    val isEmpty: Boolean get() = channels.isEmpty()

    /** How many channels a flip can visit. */
    val size: Int get() = channels.size

    /**
     * The channel after [fromNumber] in [direction], wrapping. [fromNumber] need not be in the list (a channel that has
     * gone): Up gives the lowest number above it, else wraps to the first; Down the highest below it, else the last.
     * Null when empty.
     */
    fun step(
        fromNumber: Int,
        direction: FlipDirection,
    ): ChannelAiring? =
        when (direction) {
            FlipDirection.Up -> channels.firstOrNull { it.number > fromNumber } ?: channels.firstOrNull()
            FlipDirection.Down -> channels.lastOrNull { it.number < fromNumber } ?: channels.lastOrNull()
        }

    /** The channel with [id], or null when it is not in the order (gone, or hidden). */
    fun byId(id: ChannelId): ChannelAiring? = channels.firstOrNull { it.id == id }
}
