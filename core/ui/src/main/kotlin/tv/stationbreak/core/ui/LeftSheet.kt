// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The scrim under a sheet over the guide family: black at 55%, which dims either theme. */
const val SHEET_SCRIM_ALPHA = 0.55f

/**
 * The measures of a sheet over the guide family (DESIGN.md "Sheet and Rows"), shared by every sheet that lists rows:
 * the title, body (sheet-body) and caption (sheet-caption) sizes, a row's text inset, its vertical padding, the gap
 * between rows, a row's height at least (the menu sheet's 36 dp), and the room between the content and the sheet's
 * line. [Width] is DESIGN.md's `sheet` width, from the safe area's edge.
 */
object SheetMeasures {
    val TitleSize = 24.sp
    val BodySize = 16.sp
    val CaptionSize = 14.sp
    val RowTextPadding = 10.dp
    val RowVerticalPadding = 6.dp
    val RowGap = 4.dp
    val RowMinHeight = 36.dp
    val InnerPadding = 16.dp
    val Width = 380.dp
}

private val SheetLineWidth = 1.dp

/**
 * A sheet on the screen's left edge, whatever the device's language (DESIGN.md "Sheet and Rows"): the sheet surface
 * of [LocalPanelColors] with a 1 dp line on the edge facing the screen, over a scrim of black at [SHEET_SCRIM_ALPHA]
 * when [scrim] is true (a sheet drawn over another sheet leaves it out, so the dimming does not double). [modifier] is
 * the whole screen's (the scrim), [sheetModifier] the sheet's own ground.
 *
 * [width] runs from the safe area's left edge to the line; the ground bleeds on to the screen's left, top and bottom
 * edges, while [content] keeps to the safe area of [LocalScreenFit], [innerPadding] short of the line. The content gets
 * the device's own text direction back.
 */
@Suppress("LongParameterList") // The width, the two modifiers, the scrim, the padding and the content.
@Composable
fun LeftSheet(
    width: Dp,
    modifier: Modifier = Modifier,
    sheetModifier: Modifier = Modifier,
    scrim: Boolean = true,
    innerPadding: Dp = SheetMeasures.InnerPadding,
    content: @Composable ColumnScope.() -> Unit,
) = EdgeSheet(onRight = false, width, modifier, sheetModifier, scrim, innerPadding, content)

/**
 * The same sheet on the screen's right edge (a channel's properties, #631), its line on its left. [width] runs from
 * the line to the safe area's right edge, and the content keeps [innerPadding] from the line and at least as far from
 * the screen's right edge (a viewer who chose Edge has almost no safe inset).
 */
@Suppress("LongParameterList") // As LeftSheet's.
@Composable
fun RightSheet(
    width: Dp,
    modifier: Modifier = Modifier,
    sheetModifier: Modifier = Modifier,
    scrim: Boolean = true,
    innerPadding: Dp = SheetMeasures.InnerPadding,
    content: @Composable ColumnScope.() -> Unit,
) = EdgeSheet(onRight = true, width, modifier, sheetModifier, scrim, innerPadding, content)

@Suppress("LongParameterList") // The two sheets' own parameters, and the edge.
@Composable
private fun EdgeSheet(
    onRight: Boolean,
    width: Dp,
    modifier: Modifier,
    sheetModifier: Modifier,
    scrim: Boolean,
    innerPadding: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalPanelColors.current
    val textDirection = LocalLayoutDirection.current
    val dim = if (scrim) Modifier.background(Color.Black.copy(alpha = SHEET_SCRIM_ALPHA)) else Modifier
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxSize().then(dim)) {
            val screen = DpSize(maxWidth, maxHeight)
            val safe = ScreenLayout.safeArea(screen, LocalScreenFit.current)
            val ground = sheetGround(screen, safe, innerPadding, onRight)
            val outer = ground.outer
            val line = @Composable { Box(Modifier.width(SheetLineWidth).fillMaxHeight().background(colors.sheet.line)) }
            Row(
                sheetModifier
                    .align(if (onRight) Alignment.CenterEnd else Alignment.CenterStart)
                    .width(outer + width)
                    .fillMaxHeight()
                    .background(colors.sheet.surface),
            ) {
                if (onRight) line()
                CompositionLocalProvider(LocalLayoutDirection provides textDirection) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .absolutePadding(
                                left = if (onRight) innerPadding else outer,
                                top = ground.top,
                                right = if (onRight) outer else innerPadding,
                                bottom = ground.bottom,
                            ),
                        content = content,
                    )
                }
                if (!onRight) line()
            }
        }
    }
}

/**
 * Where a sheet's content keeps clear of the screen's edges: [outer] on the sheet's own edge (the safe inset; on the
 * right, never less than the sheet's inner padding, so an Edge viewer keeps the design's gutter), and [top] and
 * [bottom]. The ground is the sheet's width plus [outer], so the text column keeps the width the design gave it.
 */
internal data class SheetGround(
    val outer: Dp,
    val top: Dp,
    val bottom: Dp,
)

internal fun sheetGround(
    screen: DpSize,
    safe: DpRect,
    innerPadding: Dp,
    onRight: Boolean,
): SheetGround =
    SheetGround(
        outer = if (onRight) (screen.width - safe.right).coerceAtLeast(innerPadding) else safe.left,
        top = safe.top,
        bottom = (screen.height - safe.bottom).coerceAtLeast(0.dp),
    )
