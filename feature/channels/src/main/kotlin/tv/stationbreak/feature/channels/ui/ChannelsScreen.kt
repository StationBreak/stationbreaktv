// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.LineupAccess
import tv.stationbreak.core.ui.BroadcastColors
import tv.stationbreak.core.ui.FocusStyle
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.feature.channels.ChannelGroup
import tv.stationbreak.feature.channels.ChannelRow
import tv.stationbreak.feature.channels.ChannelsFocus
import tv.stationbreak.feature.channels.ChannelsMessage
import tv.stationbreak.feature.channels.ChannelsUi
import tv.stationbreak.feature.channels.R

private val PanelGap = 16.dp

/**
 * The channel screen: the title and the list in the band left of [pictureHole], and the open panel below the hole, all
 * inside the safe area. The focus is drawn from [ChannelsUi.focus] (ADR-0008 addendum), not Compose's focus search.
 */
@Composable
internal fun ChannelsScreen(
    ui: ChannelsUi,
    panelFocus: PanelFocus,
    pictureHole: DpRect,
    modifier: Modifier = Modifier,
) {
    // Left to right whatever the device's language, as the picture's hole is top-right in screen coordinates.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxSize().testTag(ChannelsTags.SCREEN)) {
            val safe = ScreenLayout.safeArea(DpSize(maxWidth, maxHeight))
            val listWidth = pictureHole.left - safe.left - PanelGap
            Column(
                Modifier
                    .offset(safe.left, safe.top)
                    .width(listWidth)
                    .height(safe.bottom - safe.top)
                    .background(BroadcastColors.Ink),
            ) {
                Header(ui)
                ChannelList(ui, Modifier.weight(1f))
            }
            val open = ui.panel != null || ui.confirmingReset
            if (open) {
                ChannelsPanelView(
                    ui,
                    panelFocus,
                    Modifier
                        .offset(pictureHole.left, pictureHole.bottom + PanelGap)
                        .width(safe.right - pictureHole.left)
                        .background(BroadcastColors.Ink),
                )
            }
        }
    }
}

@Composable
private fun Header(ui: ChannelsUi) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            stringResource(R.string.channels_title),
            Modifier.testTag(ChannelsTags.TITLE),
            style = MaterialTheme.typography.titleLarge,
        )
        val readOnly = ui.access == LineupAccess.ReadOnly
        if (readOnly) NoteLine(R.string.channels_not_editable, ChannelsTags.NOT_EDITABLE)
        val message = ui.message
        // Said once: a read-only lineup already says it under the title.
        if (message != null && !(readOnly && message == ChannelsMessage.NotEditable)) {
            NoteLine(messageText(message), ChannelsTags.MESSAGE)
        }
        if (ui.choosing) NoteLine(R.string.channels_choosing, ChannelsTags.CHOOSING)
    }
}

@Composable
private fun NoteLine(
    text: Int,
    tag: String,
) {
    Text(
        stringResource(text),
        Modifier.testTag(tag),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun messageText(message: ChannelsMessage): Int =
    when (message) {
        ChannelsMessage.KidsBand -> R.string.channels_refused_kids_band
        ChannelsMessage.LastOn -> R.string.channels_refused_last_on
        ChannelsMessage.NoNumber -> R.string.channels_refused_no_number
        ChannelsMessage.NotEditable -> R.string.channels_not_editable
        ChannelsMessage.NotChosenYet -> R.string.channels_reset_not_chosen
        ChannelsMessage.SaveFailed -> R.string.channels_save_failed
        ChannelsMessage.Failed -> R.string.channels_failed
        ChannelsMessage.LibrariesUnavailable -> R.string.channels_libraries_unavailable
    }
