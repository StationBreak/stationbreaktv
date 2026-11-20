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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

// How wide a [StartSheet] is from the safe area's left edge to its line: DESIGN.md's `sheet` width.
private val StartSheetWidth = 380.dp

private val SheetEndPadding = 16.dp
private val SheetLineWidth = 1.dp

// Black dims either theme, as the menu sheet and the channel properties sheet dim what is behind them.
private const val SCRIM_ALPHA = 0.55f

/**
 * A sheet on the screen's left edge over the guide family (DESIGN.md "Sheet and Rows"): the sheet surface of
 * [LocalPanelColors] with a 1 dp line on the edge facing the screen, over a scrim of black at 55% when [scrim] is true
 * (a sheet drawn over another sheet leaves it out, so the dimming does not double). The ground bleeds to the screen's
 * left, top and bottom edges; [content] keeps to the safe area of [LocalScreenFit]. Placed left to right whatever the
 * device's language; the content gets the device's own direction back.
 */
@Composable
fun StartSheet(
    modifier: Modifier = Modifier,
    scrim: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalPanelColors.current
    val textDirection = LocalLayoutDirection.current
    val dim = if (scrim) Modifier.background(Color.Black.copy(alpha = SCRIM_ALPHA)) else Modifier
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxSize().then(dim)) {
            val screen = DpSize(maxWidth, maxHeight)
            val safe = ScreenLayout.safeArea(screen, LocalScreenFit.current)
            Row(Modifier.width(safe.left + StartSheetWidth).fillMaxHeight().background(colors.sheet.surface)) {
                CompositionLocalProvider(LocalLayoutDirection provides textDirection) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .absolutePadding(
                                left = safe.left,
                                top = safe.top,
                                right = SheetEndPadding,
                                bottom = (screen.height - safe.bottom).coerceAtLeast(0.dp),
                            ),
                        content = content,
                    )
                }
                Box(Modifier.width(SheetLineWidth).fillMaxHeight().background(colors.sheet.line))
            }
        }
    }
}
