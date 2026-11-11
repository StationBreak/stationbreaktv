// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow
import tv.stationbreak.core.domain.SyncNowState
import tv.stationbreak.core.ui.ScreenFit
import tv.stationbreak.feature.guide.CellDetails
import tv.stationbreak.feature.guide.GuideSnapshot

/** What the top-left panel shows. */
internal enum class GuidePanelKind {
    /** Nothing, its place kept: the info overlay says it already, or no grid is shown. */
    Empty,

    /** The Settings panel. */
    Settings,

    /** The focused channel's. */
    Channel,

    /** The focused programme's. */
    Details,
}

/**
 * Which panel the top-left shows: Settings wins when it is open (its rows are reached with no libraries too); the
 * info overlay makes the panel make way; with no libraries ([noLibraries], from the snapshot) no programme or channel
 * is shown, as no grid is.
 */
internal fun guidePanelOf(
    info: Boolean,
    settings: Boolean,
    noLibraries: Boolean,
    onChannel: Boolean,
): GuidePanelKind =
    when {
        info -> GuidePanelKind.Empty
        settings -> GuidePanelKind.Settings
        noLibraries -> GuidePanelKind.Empty
        onChannel -> GuidePanelKind.Channel
        else -> GuidePanelKind.Details
    }

/** What the panels read, as the guide's screen holds them. */
@Suppress("LongParameterList") // One reader for each panel there is.
internal class TopPanelParts(
    val panel: StateFlow<CellDetails?>,
    val nowOf: () -> Long?,
    val snapshotOf: () -> GuideSnapshot,
    val settings: SettingsPanel?,
    val themeChoice: GuideTheme,
    val fit: ScreenFit,
    val syncNow: SyncNowState,
    val levelling: Boolean = true,
)

/** The top-left panel of [kind], in [modifier]'s place; [GuidePanelKind.Empty] keeps the place and draws nothing. */
@Composable
internal fun TopPanel(
    kind: GuidePanelKind,
    parts: TopPanelParts,
    modifier: Modifier,
) {
    when (kind) {
        GuidePanelKind.Empty -> {
            Spacer(modifier)
        }

        GuidePanelKind.Settings -> {
            val open = checkNotNull(parts.settings)
            GuideSettingsPanel(parts.themeChoice, parts.fit, open.row, modifier, parts.syncNow, parts.levelling)
        }

        GuidePanelKind.Channel -> {
            ChannelPanel(parts.snapshotOf, modifier)
        }

        GuidePanelKind.Details -> {
            DetailsPanel(parts.panel, parts.nowOf, modifier)
        }
    }
}
