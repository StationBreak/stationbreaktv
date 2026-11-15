// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.domain.lineup.builder.RulesDescription
import tv.stationbreak.core.model.ChannelId

/**
 * Who a channel is, as the guide's channel box shows it the moment focus reaches the channel (#777): the part of
 * [ChannelProperties] that does not change with the day, read for every channel at once.
 *
 * - [kind] and [kid]: as [ChannelSummary] has them.
 * - [ceiling]: the rating ceiling, a rating-ladder name (a starter's or a custom channel's own, a leftovers channel's
 *   [ChannelProperties.leftoversCeiling]); null for none.
 * - [personality]: a starter's one line from the catalog, as [ChannelProperties.personality]; null for any other.
 * - [description]: what the channel plays ([RulesDescription], the one the properties sheet words), only for a channel
 *   with no [personality] and rules to read; null otherwise, so a starter costs no source lookups.
 */
data class ChannelIdentity(
    val kind: ChannelKind,
    val kid: Boolean,
    val ceiling: String?,
    val personality: String?,
    val description: RulesDescription?,
)

/** How the guide reads its channels' [ChannelIdentity]. Read-only; none of it throws, but for cancellation. */
interface ChannelIdentityReader {
    /**
     * The identity of every channel of the active account's lineup, on or not; empty while nobody is signed in or
     * there is no lineup.
     */
    suspend fun identities(): Map<ChannelId, ChannelIdentity>

    companion object {
        /** No channel has one: for a screen that has no lineup to read. */
        val None: ChannelIdentityReader =
            object : ChannelIdentityReader {
                override suspend fun identities(): Map<ChannelId, ChannelIdentity> = emptyMap()
            }
    }
}
