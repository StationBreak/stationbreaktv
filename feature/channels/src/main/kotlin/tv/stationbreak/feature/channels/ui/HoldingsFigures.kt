// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import tv.stationbreak.core.domain.lineup.ChannelHoldings
import tv.stationbreak.core.domain.lineup.HoldingsList
import tv.stationbreak.core.ui.HoldingsFigures

/** The words of [this] as the sheet and the guide's channel box draw them. */
internal fun ChannelHoldings.figures(): HoldingsFigures {
    val named = listed.map { it.name }
    return HoldingsFigures(hours, days, titleCount, list == HoldingsList.HomeTo, named, more)
}
