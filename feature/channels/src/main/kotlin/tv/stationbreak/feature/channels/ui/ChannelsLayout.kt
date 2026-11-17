// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * The tallest the actions panel may be: from its [top] to the safe area's [safeBottom]. Under the picture the panel
 * would otherwise be as tall as its choices, which at 960x540 with six of them runs past the bottom of the screen.
 */
internal fun panelMaxHeight(
    top: Dp,
    safeBottom: Dp,
): Dp = (safeBottom - top).coerceAtLeast(0.dp)

/**
 * How the properties sheet sits on a [screen] with [safe] text area: the text keeps [end], [top] and [bottom] clear of
 * the edges, and the ground, [width] wide, bleeds to the edge: the design's width plus the right inset less the
 * [gutter] the design already had there, so the text column keeps the room the design gave it.
 */
internal data class SheetInsets(
    val width: Dp,
    val end: Dp,
    val top: Dp,
    val bottom: Dp,
)

internal fun sheetInsets(
    screen: DpSize,
    safe: DpRect,
    designWidth: Dp,
    gutter: Dp,
): SheetInsets {
    // The text keeps to the safe area, but never closer than the gutter to the edge it had before there was a safe
    // area (a viewer who chose edge-to-edge has none); the ground grows by what the text column gives up to it.
    val end = (screen.width - safe.right).coerceAtLeast(gutter)
    return SheetInsets(
        width = designWidth + end - gutter,
        end = end,
        top = safe.top,
        bottom = (screen.height - safe.bottom).coerceAtLeast(0.dp),
    )
}
