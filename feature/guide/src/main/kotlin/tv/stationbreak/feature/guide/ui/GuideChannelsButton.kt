// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import tv.stationbreak.feature.guide.R

/** The button's own height, and the room it takes under the details panel (its height and a gap above). */
private val ButtonHeight = 28.dp
internal val ButtonRowHeight = ButtonHeight + 4.dp
private val ButtonPadding = 12.dp

/**
 * The Channels button above the grid, which opens channel management. The guide's focus is its own (model-driven),
 * so the button does not take Compose focus: [focusedOf] says whether the guide's focus is on it, and it is drawn as a
 * focused cell is (Accent with a white ring) and told to a screen reader as selected. The focus is read here, so a
 * move on the grid recomposes nothing but the cells it touched.
 */
@Composable
internal fun ChannelsButton(
    focusedOf: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val focused = focusedOf()
    val label = stringResource(R.string.guide_channels_button)
    Box(
        modifier
            .padding(top = ButtonRowHeight - ButtonHeight)
            .height(ButtonHeight)
            .background(if (focused) BroadcastColors.Accent else Color.Transparent)
            .border(2.dp, if (focused) Color.White else BroadcastColors.Ground)
            .padding(horizontal = ButtonPadding)
            .testTag(GuideTags.CHANNELS_BUTTON)
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
