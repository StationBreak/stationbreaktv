// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpRect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.feature.channels.ChannelsViewModel
import tv.stationbreak.feature.channels.builder.BuilderViewModel

/**
 * The properties sheet of [channel] (#631) on its own, drawn by its host (the program guide) over itself: what the
 * channel is and what feeds it, with the actions channel management has for it. It is [ChannelsDestination] showing
 * only the sheet, so the keys, the panels the actions open and the builder (Customize, Edit) are the ones the
 * Channels list has; the guide behind it is the list.
 *
 * [current] is what live TV is playing, which a change to the lineup may take off the air, and [openedAtNanos] is an
 * opaque token for the showing, as for [ChannelsDestination]. [onDone] is called once, when the sheet is finished: with
 * the channel to tune for Watch (or the one a change leaves to tune), else null for Back. The sheet is a layer over the
 * guide and does not outlive it.
 */
@Suppress("LongParameterList") // The sheet's channel, what plays, the showing, the hole and the finish.
@Composable
fun ChannelPropertiesDestination(
    channel: ChannelId,
    current: ChannelId?,
    openedAtNanos: Long,
    pictureHole: DpRect,
    onDone: (tune: ChannelId?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ChannelsViewModel = hiltViewModel()
    val builderViewModel: BuilderViewModel = hiltViewModel()
    ChannelsRoot(viewModel, builderViewModel, current, openedAtNanos, pictureHole, onDone, modifier, about = channel)
}
