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
import tv.stationbreak.core.ui.ScreenFit
import tv.stationbreak.feature.guide.GuideDirection
import tv.stationbreak.feature.guide.R

// On Screen fit, Left picks Safe and Right picks Edge; nothing else picks one.
internal fun SettingsPanel.fitFor(direction: GuideDirection): ScreenFit? =
    when {
        row != SettingsRow.ScreenFit -> null
        direction == GuideDirection.Left -> ScreenFit.Safe
        direction == GuideDirection.Right -> ScreenFit.Edge
        else -> null
    }

// Screen fit: its label, then Safe and Edge as the theme's two are drawn. The label is primary while the row has the
// D-pad.
@Composable
internal fun FitRow(
    fit: ScreenFit,
    focused: Boolean,
) {
    val colors = LocalGuideColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.guide_settings_screen_fit),
            style = guideText(13.sp, FontWeight.Normal),
            color = if (focused) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
        )
        val safe = fit == ScreenFit.Safe
        val edge = fit == ScreenFit.Edge
        PanelTab(R.string.guide_fit_safe, GuideTags.FIT_SAFE, tabLook(safe && focused, safe, colors), safe)
        PanelTab(R.string.guide_fit_edge, GuideTags.FIT_EDGE, tabLook(edge && focused, edge, colors), edge)
    }
}
