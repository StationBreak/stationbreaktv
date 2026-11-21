// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.AdultMark

// A built-in channel whose copies are adult content (#984): the opt-in After Hours. Read from the stored channel,
// never from a draft. The one reading, for create, save and previews.
internal fun LineupChannel.isAdultBuiltIn(): Boolean {
    val starter = source as? ChannelSource.Starter
    return starter?.recipeKey == AdultMark.AFTER_HOURS_KEY
}

/** True when [origin] is an adult built-in channel of this lineup. */
internal fun Lineup.isAdultOrigin(origin: ChannelId?): Boolean {
    val channel = channels.firstOrNull { it.id == origin }
    return channel?.isAdultBuiltIn() == true
}
