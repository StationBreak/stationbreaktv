// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.BroadcastColors
import tv.stationbreak.feature.guide.GuideMenuEntry
import tv.stationbreak.feature.guide.R

/** An entry's own height, and the room it takes under the details panel (its height and a gap above). */
private val ButtonHeight = 28.dp
internal val ButtonRowHeight = ButtonHeight + 4.dp
private val ButtonPadding = 12.dp
private val EntryGap = 8.dp

/**
 * The menu row above the grid: Channels, which opens channel management, and Exit, which leaves live TV. The guide's
 * focus is its own (model-driven), so the entries do not take Compose focus: [menuOf] says which one the guide's focus
 * is on, if any. Each is drawn as a focused cell is (Accent with a white ring) and told to a screen reader as
 * selected. The focus is read through a derived state per entry, so an entry recomposes only when the focus reaches or
 * leaves it, never for a move on the grid.
 */
@Composable
internal fun GuideMenu(
    menuOf: () -> GuideMenuEntry?,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(EntryGap)) {
        MenuButton(R.string.guide_channels_button, GuideTags.CHANNELS_BUTTON) { menuOf() == GuideMenuEntry.Channels }
        MenuButton(R.string.guide_exit_button, GuideTags.EXIT_BUTTON) { menuOf() == GuideMenuEntry.Exit }
    }
}

@Composable
private fun MenuButton(
    @StringRes text: Int,
    tag: String,
    focusedOf: () -> Boolean,
) {
    val focused by remember(focusedOf) { derivedStateOf(focusedOf) }
    val label = stringResource(text)
    Box(
        Modifier
            .padding(top = ButtonRowHeight - ButtonHeight)
            .height(ButtonHeight)
            .background(if (focused) BroadcastColors.Accent else Color.Transparent)
            .border(2.dp, if (focused) Color.White else BroadcastColors.Ground)
            .padding(horizontal = ButtonPadding)
            .testTag(tag)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                selected = focused
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            style = mono(16.sp, LETTER_SPACING.sp),
            color = if (focused) BroadcastColors.Ink else BroadcastColors.Cream,
            maxLines = 1,
        )
    }
}
