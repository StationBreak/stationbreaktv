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
 * An entry's own height (at least 40 dp, so the label has room), and the room it takes under the details panel.
 * The visible pill is this tall and fully rounded. A 40 dp target can surround a slightly shorter pill; here they
 * are the same, so the hit target and the pill match.
 */
private val ButtonHeight = 40.dp
internal val ButtonRowHeight = ButtonHeight + 4.dp
private val ButtonPadding = 16.dp
private val EntryGap = 8.dp

/**
 * The menu row above the grid: Channels, Settings and Exit. The guide's focus is its own (model-driven), so the
 * entries do not take Compose focus and do not grow ([CONTENT_SCALE]). [menuOf] says which one the guide's focus is
 * on, if any. The focused entry is a filled pill with no ring. While the focus is on the grid, Channels keeps the
 * quiet selected pill (it is the primary action, not the D-pad focus) and the others are transparent. Each is told
 * to a screen reader as selected only while the guide's focus is on it. An entry recomposes only when that changes.
 */
@Composable
internal fun GuideMenu(
    menuOf: () -> GuideMenuEntry?,
    modifier: Modifier = Modifier,
) {
    val onGridOf = remember(menuOf) { { menuOf() == null } }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(EntryGap)) {
        MenuButton(
            R.string.guide_channels_button,
            GuideTags.CHANNELS_BUTTON,
            primary = true,
            onGridOf = onGridOf,
        ) { menuOf() == GuideMenuEntry.Channels }
        MenuButton(
            R.string.guide_settings_button,
            GuideTags.SETTINGS_BUTTON,
            primary = false,
            onGridOf = onGridOf,
        ) { menuOf() == GuideMenuEntry.Settings }
        MenuButton(
            R.string.guide_exit_button,
            GuideTags.EXIT_BUTTON,
            primary = false,
            onGridOf = onGridOf,
        ) { menuOf() == GuideMenuEntry.Exit }
    }
}

@Composable
private fun MenuButton(
    @StringRes text: Int,
    tag: String,
    primary: Boolean,
    onGridOf: () -> Boolean,
    focusedOf: () -> Boolean,
) {
    val focused by remember(focusedOf) { derivedStateOf(focusedOf) }
    val onGrid by remember(onGridOf) { derivedStateOf(onGridOf) }
    val colors = LocalGuideColors.current
    val quiet = primary && onGrid && !focused
    val label = stringResource(text)
    val fill =
        when {
            focused -> colors.menuPillFocused
            quiet -> colors.menuPillSelected
            else -> null
        }
    val textColor =
        when {
            focused -> colors.menuTextFocused
            quiet -> colors.menuTextSelected
            else -> colors.menuTextUnselected
        }
    Box(
        Modifier
            .padding(top = ButtonRowHeight - ButtonHeight)
            .height(ButtonHeight)
            .drawBehind { drawGuidePill(fill) }
            .padding(horizontal = ButtonPadding)
            .testTag(tag)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                selected = focused
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = guideText(18.sp, if (focused || quiet) FontWeight.SemiBold else FontWeight.Medium),
            color = textColor,
            maxLines = 1,
        )
    }
}
