// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import tv.stationbreak.core.domain.lineup.builder.PlaysRow
import tv.stationbreak.core.domain.lineup.builder.playsRowsOf
import tv.stationbreak.core.model.ChannelId

/**
 * Who a channel is, as the guide's channel box shows it the moment focus reaches the channel (#777): the part of
 * [ChannelProperties] that does not change with the day, read for every channel at once.
 *
 * - [kind] and [kid]: as [ChannelSummary] has them.
 * - [ceiling]: the rating ceiling, a rating-ladder name (a starter's or a custom channel's own, a leftovers channel's
 *   [ChannelProperties.leftoversCeiling]); null for none.
 * - [personality]: a starter's one line from the catalog, as [ChannelProperties.personality]; null for any other.
 * - [plays]: the tag line's source for a channel with no [personality]: the first row of what it plays
 *   ([playsRowsOf], the rows the properties sheet words). Null for a channel with a personality (its sources are not
 *   looked up, which costs a starter nothing), and for one with nothing to list. Not a general description: read
 *   [ChannelProperties.description] for that.
 */
data class ChannelIdentity(
    val kind: ChannelKind,
    val kid: Boolean,
    val ceiling: String?,
    val personality: String?,
    val plays: PlaysRow?,
)

/** How the guide reads its channels' [ChannelIdentity]. Read-only; none of it throws, but for cancellation. */
interface ChannelIdentityReader {
    /**
     * The identity of every channel of the active account's lineup, on or not; empty while nobody is signed in or
     * there is no lineup. What needs no lookup comes first (every channel, with no [ChannelIdentity.plays]), then the
     * whole map once the sources of the channels without a personality are named; a lineup read before costs no
     * lookups. It ends after the whole map.
     */
    fun identities(): Flow<Map<ChannelId, ChannelIdentity>>

    companion object {
        /** No channel has one: for a screen that has no lineup to read. */
        val None: ChannelIdentityReader =
            object : ChannelIdentityReader {
                override fun identities(): Flow<Map<ChannelId, ChannelIdentity>> = flowOf(emptyMap())
            }
    }
}
