// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The tallest the actions panel may be: from its [top] to the safe area's [safeBottom]. Under the picture the panel
 * would otherwise be as tall as its choices, which at 960x540 with six of them runs past the bottom of the screen.
 */
internal fun panelMaxHeight(
    top: Dp,
    safeBottom: Dp,
): Dp = (safeBottom - top).coerceAtLeast(0.dp)
