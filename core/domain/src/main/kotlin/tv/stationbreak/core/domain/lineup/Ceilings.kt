// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.ChannelCeilings
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.StarterCatalogView

/** The rating ceiling a channel is scheduled under: [ChannelCeilings.of] over this [catalog]. */
internal class Ceilings(
    private val catalog: StarterCatalogView,
) {
    /** The ceiling [channel] is scheduled under, or null for none. */
    fun of(channel: LineupChannel): String? = ChannelCeilings.of(channel, catalog)
}
