// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.feature.guide.R

/**
 * Dark and Light, in the details band, while the Settings entry is open. [choice] is the one under the D-pad. Picking
 * one is done by the screen; this only draws it. Neither pill scales or draws a ring.
 */
@Composable
internal fun GuideThemePanel(
    choice: GuideTheme,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGuideColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.guide_settings_button),
            style = guideText(28.sp, FontWeight.SemiBold),
            color = colors.textPrimary,
            maxLines = 1,
        )
        Text(
            text = stringResource(R.string.guide_settings_appearance),
            style = guideText(18.sp, FontWeight.Normal),
            color = colors.textSecondary,
            maxLines = 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            ThemeChoice(R.string.guide_theme_dark, GuideTags.THEME_DARK, choice == GuideTheme.Dark)
            ThemeChoice(R.string.guide_theme_light, GuideTags.THEME_LIGHT, choice == GuideTheme.Light)
        }
    }
}

@Composable
private fun ThemeChoice(
    label: Int,
    tag: String,
    chosen: Boolean,
) {
    val colors = LocalGuideColors.current
    Text(
        text = stringResource(label),
        modifier =
            Modifier
                .underline(if (chosen) colors.textPrimary else null)
                .testTag(tag)
                .semantics {
                    role = Role.Button
                    selected = chosen
                },
        style = guideText(13.sp, FontWeight.Medium),
        color = if (chosen) colors.textPrimary else colors.textSecondary,
        maxLines = 1,
    )
}
