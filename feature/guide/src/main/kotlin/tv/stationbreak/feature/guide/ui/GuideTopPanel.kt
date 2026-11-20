// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow
import tv.stationbreak.core.domain.lineup.ChannelIdentity
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.feature.guide.CellDetails
import tv.stationbreak.feature.guide.GuideSnapshot
import tv.stationbreak.feature.guide.NoHoldings
import tv.stationbreak.feature.guide.ShownHoldings

/** What the top-left panel shows. */
internal enum class GuidePanelKind {
    /** The first visit's note (#94), until the first key. */
    FirstVisit,

    /** Nothing, its place kept: no grid is shown. */
    Empty,

    /** The focused channel's. */
    Channel,

    /** The focused programme's. */
    Details,
}

/**
 * Which panel the top-left shows: with no libraries ([noLibraries], from the snapshot) no programme or channel is
 * shown, as no grid is. The Settings sheet is drawn over the guide, not here.
 */
internal fun guidePanelOf(
    noLibraries: Boolean,
    onChannel: Boolean,
    firstVisit: Boolean = false,
): GuidePanelKind =
    when {
        firstVisit -> GuidePanelKind.FirstVisit
        noLibraries -> GuidePanelKind.Empty
        onChannel -> GuidePanelKind.Channel
        else -> GuidePanelKind.Details
    }

/** What the panels read, as the guide's screen holds them. */
internal class TopPanelParts(
    val panel: StateFlow<CellDetails?>,
    val nowOf: () -> Long?,
    val snapshotOf: () -> GuideSnapshot,
    val channelBox: ChannelBoxReaders = ChannelBoxReaders(),
)

/** What the channel box reads: the focused channel's holdings (#757) and every channel's identity (#777). */
internal class ChannelBoxReaders(
    val holdings: StateFlow<ShownHoldings?> = NoHoldings,
    val identities: StateFlow<Map<ChannelId, ChannelIdentity>> = NoIdentities,
)

/**
 * The top-left panel of [kind], in [modifier]'s place; [GuidePanelKind.Empty] keeps the place and draws nothing. The
 * details panel says when its channel is the [current] one, the channel playing.
 */
@Composable
internal fun TopPanel(
    kind: GuidePanelKind,
    parts: TopPanelParts,
    modifier: Modifier,
    current: ChannelId? = null,
) {
    when (kind) {
        GuidePanelKind.Empty -> {
            Spacer(modifier)
        }

        GuidePanelKind.FirstVisit -> {
            FirstVisitNote(modifier)
        }

        GuidePanelKind.Channel -> {
            ChannelPanel(parts.snapshotOf, parts.channelBox, parts.nowOf, modifier)
        }

        GuidePanelKind.Details -> {
            DetailsPanel(parts.panel, parts.nowOf, modifier, current)
        }
    }
}
