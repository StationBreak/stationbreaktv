// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme

/** The app's theme: tv-material's defaults for now. Retro theming and design tokens are later work. */
@Composable
fun StationBreakTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
