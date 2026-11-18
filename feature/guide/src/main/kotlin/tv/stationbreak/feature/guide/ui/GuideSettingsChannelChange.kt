// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.ChannelChange
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.feature.guide.GuideDirection
import tv.stationbreak.feature.guide.R

// On Changing channel, Left picks Stan and Right picks Snow; nothing else picks one.
internal fun SettingsPanel.channelChangeFor(direction: GuideDirection): ChannelChange? =
    when {
        row != SettingsRow.ChannelChange -> null
        direction == GuideDirection.Left -> ChannelChange.Stan
        direction == GuideDirection.Right -> ChannelChange.Snow
        else -> null
    }

// Changing channel: its label, then Stan and Snow as the screen fit's two are drawn. The label is primary while the row
// has the D-pad.
@Composable
internal fun ChannelChangeRow(
    change: ChannelChange,
    focused: Boolean,
) {
    val colors = LocalGuideColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.guide_settings_channel_change),
            style = guideText(13.sp, FontWeight.Normal),
            color = if (focused) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
        )
        val stan = change == ChannelChange.Stan
        val snow = change == ChannelChange.Snow
        PanelTab(
            R.string.guide_channel_change_stan,
            GuideTags.CHANNEL_CHANGE_STAN,
            tabLook(stan && focused, stan, colors),
            stan,
        )
        PanelTab(
            R.string.guide_channel_change_snow,
            GuideTags.CHANNEL_CHANGE_SNOW,
            tabLook(snow && focused, snow, colors),
            snow,
        )
    }
}
