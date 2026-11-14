// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.StateFlow
import tv.stationbreak.core.ui.ChannelHoldingsLines
import tv.stationbreak.core.ui.HoldingsTags
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.guideFigures
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.core.ui.mark.ChannelMark
import tv.stationbreak.feature.guide.GuideSnapshot
import tv.stationbreak.feature.guide.R
import tv.stationbreak.feature.guide.ShownHoldings
import tv.stationbreak.feature.guide.focusedRow

// The mark at twice its 50 by 28 dp column box (#631).
private val LargeMarkWidth = 100.dp
private val LargeMarkHeight = 56.dp
private val MarkGap = 12.dp
private val HoldingsGap = 8.dp

/**
 * The details panel while the focus is on the channel column (#631): the channel's mark, its number and its name, in
 * the place the focused program's details take otherwise, and below them (#757) what the channel holds: its hours, and
 * the titles that live there ("Home to:") or, with none, those with the most hours ("Mostly:"). The holdings come once
 * focus has rested on the channel, and only the focused channel's are drawn; a channel that is off or has nothing today
 * shows none. Read out as it changes, like the program's, since a screen reader cannot see the guide's own focus. Only
 * the focused row is read, and only while this shows.
 */
@Composable
internal fun ChannelPanel(
    snapshotOf: () -> GuideSnapshot,
    holdings: StateFlow<ShownHoldings?>,
    modifier: Modifier,
) {
    val row by remember(snapshotOf) { derivedStateOf { snapshotOf().focusedRow() } }
    val shown = row ?: return
    val colors = LocalGuideColors.current
    val held = holdings.collectAsStateWithLifecycle().value?.takeIf { it.channel == shown.channel }
    Column(
        modifier
            .clipToBounds()
            .testTag(GuideTags.CHANNEL_PANEL)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelMark(shown.markKey, shown.initials, Modifier.size(LargeMarkWidth, LargeMarkHeight))
            Spacer(Modifier.width(MarkGap))
            Column {
                Text(
                    text = stringResource(R.string.guide_channel_number, shown.number).uppercase(),
                    style = guideFigures(11.sp, FontWeight.Normal),
                    color = colors.textSecondary,
                    maxLines = 1,
                )
                Text(
                    text = shown.name,
                    modifier = Modifier.testTag(GuideTags.CHANNEL_PANEL_NAME),
                    style = guideText(20.sp, FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (held != null) {
            Spacer(Modifier.height(HoldingsGap))
            ChannelHoldingsLines(
                hours = held.holdings.hours,
                days = held.holdings.days,
                titles = held.holdings.titleCount,
                homeTo = held.holdings.homeTo,
                names = held.holdings.listedNames,
                more = held.holdings.more,
                primary = colors.textPrimary,
                secondary = colors.textSecondary,
                tags = HoldingsLineTags,
            )
        }
    }
}

private val HoldingsLineTags =
    HoldingsTags(GuideTags.CHANNEL_PANEL_HOURS, GuideTags.CHANNEL_PANEL_LIST_LABEL, GuideTags.CHANNEL_PANEL_LIST)
