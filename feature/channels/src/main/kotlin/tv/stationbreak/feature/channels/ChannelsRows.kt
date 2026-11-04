// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels

import tv.stationbreak.core.domain.lineup.ChannelChange
import tv.stationbreak.core.domain.lineup.ChannelStatus
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.model.ChannelId

// What the channel screen derives from the lineup's channels, apart from the ViewModel's own state.

/** [channels] as rows: On, then Shelf, then Off, in number order inside each (numberless last). */
internal fun channelRows(
    channels: List<ChannelSummary>,
    reasons: Map<ChannelId, HiddenReason?>,
): List<ChannelRow> =
    channels
        .sortedWith(compareBy<ChannelSummary>({ groupOf(it) }, { it.number ?: Int.MAX_VALUE }))
        .map { c ->
            val group = groupOf(c)
            ChannelRow(
                id = c.id,
                number = c.number,
                name = c.name,
                group = group,
                kind = c.kind,
                kid = c.kid,
                optIn = c.optIn,
                reason = if (group == ChannelGroup.On) reasons[c.id] else null,
                // A switched-off row never explains a shelf; an On row's is the leave the planner set.
                shelfReason = if (group == ChannelGroup.Off) null else c.shelfReason,
                hasOverride = c.defaultNumber != null && c.number != c.defaultNumber,
            )
        }

private fun groupOf(c: ChannelSummary): ChannelGroup =
    when (c.state) {
        ChannelStatus.On -> ChannelGroup.On
        ChannelStatus.Shelf -> ChannelGroup.Shelf
        ChannelStatus.UserHidden -> ChannelGroup.Off
    }

/** The channel a change is for; the lineup-wide "Use my collections" switch is for none. */
internal val ChannelChange.channelId: ChannelId?
    get() =
        when (this) {
            is ChannelChange.SwitchOn -> id
            is ChannelChange.SwitchOff -> id
            is ChannelChange.SetNumber -> id
            is ChannelChange.ClearNumber -> id
            is ChannelChange.UseMyCollections -> null
        }

/**
 * The panel that offers the number swap after [change] was refused because [holder] has the number; null when the
 * change is not a number change or has no number to offer.
 */
internal fun swapPanelFor(
    change: ChannelChange,
    channels: List<ChannelSummary>,
    holder: ChannelId,
): ChannelsPanel? {
    val name =
        channels
            .firstOrNull { it.id == holder }
            ?.name
            .orEmpty()
    return when (change) {
        is ChannelChange.SetNumber -> {
            ChannelsPanel.Swap(change.id, change.number, name, toDefault = false)
        }

        is ChannelChange.ClearNumber -> {
            val number = channels.firstOrNull { it.id == change.id }?.defaultNumber
            number?.let { ChannelsPanel.Swap(change.id, it, name, toDefault = true) }
        }

        else -> {
            null
        }
    }
}
