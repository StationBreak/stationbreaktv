// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colours of a panel that is drawn over the guide (Settings, Libraries): its ground, its text, its quieter text
 * and its focus ring. The guide provides these from its own Dark or Light choice through [LocalPanelColors], so a
 * panel follows the guide's appearance without a feature depending on the guide. [Dark] is the default, for a panel
 * shown with no guide around it. Text colours may carry an alpha; contrast is judged on the composite over [ground].
 */
class PanelColors(
    val ground: Color,
    val text: Color,
    val secondary: Color,
    val ring: Color,
) {
    companion object {
        val Dark =
            PanelColors(
                ground = BroadcastColors.Ink,
                text = BroadcastColors.Cream,
                secondary = StationBreakDarkColors.onSurfaceVariant,
                ring = StationBreakDarkColors.primary,
            )
    }
}

/** Dark unless the guide provides its Light choice. Never the system theme. */
val LocalPanelColors = staticCompositionLocalOf { PanelColors.Dark }
