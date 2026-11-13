// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.BroadcastColors
import tv.stationbreak.core.ui.GuideColors
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.StationBreakDarkColors
import tv.stationbreak.core.ui.drawnFocus
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.feature.guide.GuideMenuEntry
import tv.stationbreak.feature.guide.R

private val UnderlineGap = 4.dp
private val UnderlineHeight = 1.dp
private val FocusUnderlineHeight = 2.dp

/** The menu sheet's measures (#756): a left-edge sheet 220 dp wide, full height, with rows 36 dp tall. */
internal val MenuSheetWidth = 220.dp
internal val MenuRowHeight = 36.dp
private val SheetEndPadding = 8.dp
private val RowTextPadding = 10.dp
private val RowGap = 4.dp
private val SheetLineWidth = 1.dp
private const val MENU_ROW_SP = 13
private const val HINT_SP = 11

// The properties sheet's own surface (`ChannelsPalette` in feature:channels, which this feature cannot read): the
// design's #141518 with a 1 dp line on the edge toward the screen, over a scrim of black at 55%.
private val SheetGround = Color(0xFF141518)
private val SheetLine = Color(0xFF3A3B40)
private const val SCRIM_ALPHA = 0.55f

/**
 * The menu sheet (#756): Channels, Settings and Exit as rows of a sheet on the screen's left edge, over the guide
 * dimmed as the channel properties sheet dims its list, with the same surface and the same drawn focus. The guide's
 * focus is its own (model-driven), so the rows take no Compose focus. [menuOf] says which row the guide's focus is on;
 * the sheet is only composed while it is not null. Each row is told to a screen reader as selected only while the focus
 * is on it. [startInset], [topInset] and [bottomInset] keep the rows inside the safe area.
 */
@Composable
internal fun GuideMenuSheet(
    menuOf: () -> GuideMenuEntry?,
    startInset: Dp,
    topInset: Dp,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val back = stringResource(R.string.guide_key_back)
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = SCRIM_ALPHA)).testTag(GuideTags.MENU_SCRIM)) {
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .width(MenuSheetWidth)
                .fillMaxHeight()
                .background(SheetGround)
                .testTag(GuideTags.MENU_SHEET),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = startInset, top = topInset, end = SheetEndPadding),
                verticalArrangement = Arrangement.spacedBy(RowGap),
            ) {
                MenuRow(R.string.guide_channels_button, GuideTags.CHANNELS_BUTTON) {
                    menuOf() == GuideMenuEntry.Channels
                }
                MenuRow(R.string.guide_settings_button, GuideTags.SETTINGS_BUTTON) {
                    menuOf() == GuideMenuEntry.Settings
                }
                MenuRow(R.string.guide_exit_button, GuideTags.EXIT_BUTTON) {
                    menuOf() == GuideMenuEntry.Exit
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "$back  ${stringResource(R.string.guide_hint_close_menu)}",
                    modifier =
                        Modifier
                            .padding(start = RowTextPadding, bottom = bottomInset)
                            .testTag(GuideTags.MENU_HINT),
                    style = guideText(HINT_SP.sp, FontWeight.Normal),
                    color = StationBreakDarkColors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Box(Modifier.width(SheetLineWidth).fillMaxHeight().background(SheetLine))
        }
    }
}

@Composable
private fun MenuRow(
    @StringRes text: Int,
    tag: String,
    focusedOf: () -> Boolean,
) {
    val focused by remember(focusedOf) { derivedStateOf(focusedOf) }
    Text(
        text = stringResource(text),
        modifier =
            Modifier
                .testTag(tag)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    selected = focused
                }.fillMaxWidth()
                .height(MenuRowHeight)
                .drawnFocus(focused)
                .padding(horizontal = RowTextPadding)
                .wrapContentHeight(Alignment.CenterVertically),
        style = guideText(MENU_ROW_SP.sp, FontWeight.Medium),
        color = BroadcastColors.Cream,
        maxLines = 1,
    )
}

/** How a tab is drawn: its text colour, and its underline (null for none) and the underline's thickness. */
internal data class TabLook(
    val text: Color,
    val underline: Color?,
    val thickness: Dp,
)

/**
 * Three states, told apart by the underline: focused (the guide's focus is on the tab) is primary text over a 2 dp
 * accent line; selected (Channels while the focus is on the grid) is primary text over a 1 dp quiet line; any other
 * tab is secondary text with none. The accent line is the D-pad focus, the one thing the accent says on this row.
 */
internal fun tabLook(
    focused: Boolean,
    selected: Boolean,
    colors: GuideColors,
): TabLook =
    when {
        focused -> TabLook(colors.textPrimary, colors.accent, FocusUnderlineHeight)
        selected -> TabLook(colors.textPrimary, colors.line, UnderlineHeight)
        else -> TabLook(colors.textSecondary, null, UnderlineHeight)
    }

/** A rule 4 dp under the text, the width of the text, [thickness] thick, when [color] is not null. Reserves 2 dp. */
internal fun Modifier.underline(
    color: Color?,
    thickness: Dp = UnderlineHeight,
): Modifier =
    padding(bottom = UnderlineGap + FocusUnderlineHeight).drawBehind {
        if (color != null) {
            val top = size.height + UnderlineGap.toPx()
            drawRect(color, Offset(0f, top), Size(size.width, thickness.toPx()))
        }
    }
