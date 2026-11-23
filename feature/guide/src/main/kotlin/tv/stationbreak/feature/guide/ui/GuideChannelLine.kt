// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.lineup.bandOf
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.guideFigures
import tv.stationbreak.feature.guide.CellDetails

private val BandGap = 10.dp

/**
 * The details panel's channel line: the station bug, "number · name" (and "playing"), then the number's band flag, so
 * the number says what it is for (#1100). The flag is spoken once, here, with the line.
 */
@Composable
internal fun ChannelLineRow(
    details: CellDetails,
    playingWord: String?,
) {
    val colors = LocalGuideColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        StationBug()
        Text(
            text = channelLine(details.channelNumber, details.channelName, playingWord),
            modifier = Modifier.weight(1f, fill = false).testTag(GuideTags.PANEL_CHANNEL),
            style = guideFigures(11.sp, FontWeight.Normal),
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        bandOf(details.channelNumber)?.let {
            Spacer(Modifier.width(BandGap))
            PanelBandFlag(it, colors)
        }
    }
}
