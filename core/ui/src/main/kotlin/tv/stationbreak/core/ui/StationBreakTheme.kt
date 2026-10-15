// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * tv-material's dark scheme, with one change: `border` is the focus ring on a focused button. A focused button fills
 * with `onSurface` (near white), so the ring has to be a mid tone that is at least 3:1 against both that fill and the
 * page; `primary` is only 1.3:1 against the fill and vanishes. `ContrastTest` checks the numbers.
 */
val StationBreakDarkColors = darkColorScheme(border = Color(0xFF7E74B0))

/** How strong the address hint is, as the alpha of `onSurfaceVariant` on the field fill. 0.9 keeps it above 4.5:1. */
const val HINT_ALPHA = 0.9f

/** The app's theme: tv-material's dark scheme, set explicitly (a TV is watched in a dim room). */
@Composable
fun StationBreakTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StationBreakDarkColors, content = content)
}
