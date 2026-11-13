// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.stationbreak.core.domain.lineup.ChannelHoldings
import tv.stationbreak.core.model.ChannelId

/** What [channel] holds, read once focus had rested on its column: the channel box draws it for that channel only. */
internal class ShownHoldings(
    val channel: ChannelId,
    val holdings: ChannelHoldings,
)

/** No holdings: the one way to say so, for a guide with no lineup to read them from. */
internal val NoHoldings: StateFlow<ShownHoldings?> = MutableStateFlow<ShownHoldings?>(null).asStateFlow()
