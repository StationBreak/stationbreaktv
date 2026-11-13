// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import tv.stationbreak.core.domain.lineup.ChannelHoldings
import tv.stationbreak.core.domain.lineup.HoldingsList
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.HoldingsFigures

/** What [channel] holds, read once focus had rested on its column: the channel box draws it for that channel only. */
internal class ShownHoldings(
    val channel: ChannelId,
    val holdings: ChannelHoldings,
)

/** The words of [this] as the channel box and the properties sheet draw them. */
internal fun ChannelHoldings.figures() = HoldingsFigures(hours, days, titleCount, list == HoldingsList.HomeTo, listed.map { it.name }, more)
