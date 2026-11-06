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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.BroadcastFocus
import tv.stationbreak.core.ui.drawBroadcastFocus
import tv.stationbreak.core.ui.drawRestingOutline
import tv.stationbreak.feature.guide.GuideMenuEntry
import tv.stationbreak.feature.guide.R

/**
 * An entry's own height (at least 40 dp, so a 16 sp label has room inside the focus ring), and the room it takes under
 * the details panel (its height and a gap above).
 */
private val ButtonHeight = 40.dp
internal val ButtonRowHeight = ButtonHeight + 4.dp
private val ButtonPadding = 12.dp
private val EntryGap = 8.dp

/**
 * The menu row above the grid: Channels, which opens channel management, and Exit, which leaves live TV. The guide's
 * focus is its own (model-driven), so the entries do not take Compose focus: [menuOf] says which one the guide's focus
 * is on, if any. Each is drawn with [BroadcastFocus] (the same plate as a focused cell, at button scale) and told to a
 * screen reader as selected. The focus is read through a derived state per entry, so an entry recomposes
 * only when the focus reaches or leaves it, never for a move on the grid.
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
            .graphicsLayer {
                // Resting stays at the cell scale (1): the layout box does not change. A focused button grows in the
                // draw, so the row does not reflow.
                val scale = if (focused) BroadcastFocus.BUTTON_SCALE else BroadcastFocus.CELL_SCALE
                scaleX = scale
                scaleY = scale
            }.drawBehind {
                if (focused) {
                    drawBroadcastFocus(0f, 0f, size.width, size.height)
                } else {
                    drawRestingOutline(0f, 0f, size.width, size.height)
                }
            }.padding(horizontal = ButtonPadding)
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
            color = if (focused) BroadcastFocus.content else BroadcastFocus.restingContent,
            maxLines = 1,
        )
    }
}
