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
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.feature.guide.GuideDirection
import tv.stationbreak.feature.guide.R

/** The log name of the path that picked [direction] on the row: Left saves Off, Right saves On. */
internal fun SettingsPanel.levellingSourceFor(direction: GuideDirection): String? =
    when {
        levellingFor(direction) == null -> null
        direction == GuideDirection.Left -> "settingsLeft"
        else -> "settingsRight"
    }

// On Even out volume, Left picks Off and Right picks On; nothing else picks it.
internal fun SettingsPanel.levellingFor(direction: GuideDirection): Boolean? =
    when {
        row != SettingsRow.VolumeLevelling -> null
        direction == GuideDirection.Left -> false
        direction == GuideDirection.Right -> true
        else -> null
    }

// Even out volume: its label, then Off and On as the screen fit's two are drawn. The label is primary while the row has
// the D-pad.
@Composable
internal fun LevellingRow(
    enabled: Boolean,
    focused: Boolean,
) {
    val colors = LocalGuideColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.guide_settings_levelling),
            style = guideText(13.sp, FontWeight.Normal),
            color = if (focused) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
        )
        val off = !enabled
        PanelTab(R.string.guide_levelling_off, GuideTags.LEVELLING_OFF, tabLook(off && focused, off, colors), off)
        val on = enabled
        PanelTab(R.string.guide_levelling_on, GuideTags.LEVELLING_ON, tabLook(on && focused, on, colors), on)
    }
}
