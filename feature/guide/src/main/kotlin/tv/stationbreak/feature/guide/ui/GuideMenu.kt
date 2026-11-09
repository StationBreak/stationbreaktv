// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import tv.stationbreak.feature.guide.GuideMenuEntry
import tv.stationbreak.feature.guide.R

/**
 * The tabs' row: 28 dp, the text at its foot over a 1 dp underline when active. It takes the foot of the details band
 * (the picture corner's height), so the panel has what is left.
 */
internal val ButtonRowHeight = 28.dp
private val TabGap = 20.dp
private val UnderlineGap = 4.dp
private val UnderlineHeight = 1.dp

/**
 * The menu row above the grid: Channels, Settings and Exit as plain text tabs, no pills. The guide's focus is its own
 * (model-driven), so the tabs do not take Compose focus. [menuOf] says which one the guide's focus is on, if any. The
 * active tab is in the primary colour with a 1 dp underline: the focused one, or Channels while the focus is on the
 * grid (it is the screen the guide is on, not the D-pad focus). Exactly one is underlined at a time. Each is told to
 * a screen reader as selected only while the guide's focus is on it. A tab recomposes only when that changes.
 */
@Composable
internal fun GuideMenu(
    menuOf: () -> GuideMenuEntry?,
    modifier: Modifier = Modifier,
) {
    val onGridOf = remember(menuOf) { { menuOf() == null } }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(TabGap)) {
        MenuTab(R.string.guide_channels_button, GuideTags.CHANNELS_BUTTON, primary = true, onGridOf = onGridOf) {
            menuOf() == GuideMenuEntry.Channels
        }
        MenuTab(R.string.guide_settings_button, GuideTags.SETTINGS_BUTTON, primary = false, onGridOf = onGridOf) {
            menuOf() == GuideMenuEntry.Settings
        }
        MenuTab(R.string.guide_exit_button, GuideTags.EXIT_BUTTON, primary = false, onGridOf = onGridOf) {
            menuOf() == GuideMenuEntry.Exit
        }
    }
}

@Composable
private fun MenuTab(
    @StringRes text: Int,
    tag: String,
    primary: Boolean,
    onGridOf: () -> Boolean,
    focusedOf: () -> Boolean,
) {
    val focused by remember(focusedOf) { derivedStateOf(focusedOf) }
    val onGrid by remember(onGridOf) { derivedStateOf(onGridOf) }
    val colors = LocalGuideColors.current
    val active = focused || (primary && onGrid)
    Box(
        Modifier
            .height(ButtonRowHeight)
            .testTag(tag)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                selected = focused
            },
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            text = stringResource(text),
            modifier = Modifier.underline(if (active) colors.textPrimary else null),
            style = guideText(11.sp, FontWeight.Medium),
            color = if (active) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
        )
    }
}

/** A 1 dp rule 4 dp under the text, the width of the text, when [color] is not null. */
internal fun Modifier.underline(color: Color?): Modifier =
    padding(bottom = UnderlineGap + UnderlineHeight).drawBehind {
        if (color != null) {
            val top = size.height + UnderlineGap.toPx()
            drawRect(color, Offset(0f, top), Size(size.width, UnderlineHeight.toPx()))
        }
    }
