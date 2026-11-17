// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colours of a panel that is drawn over the guide (Settings, Libraries): its ground, its text, its quieter text
 * and its focus ring. The guide provides these from its own Dark or Light tokens through [LocalPanelColors], so a
 * panel follows the guide's appearance without a feature depending on the guide. Inside the guide the panel takes the
 * guide's tokens in both themes, so Dark there is the guide's own (its page, white text, orange ring). [Dark] here is
 * only the default for a panel shown with no guide around it (Ink, Cream and the theme's primary ring). Text colours
 * may carry an alpha; contrast is judged on the composite over [ground]. [sheet] is the sheet over the guide; text on
 * its surface is [text] and [secondary].
 */
class PanelColors(
    val ground: Color,
    val text: Color,
    val secondary: Color,
    val ring: Color,
    val sheet: SheetColors,
) {
    companion object {
        val Dark =
            PanelColors(
                ground = BroadcastColors.Ink,
                text = BroadcastColors.Cream,
                secondary = StationBreakDarkColors.onSurfaceVariant,
                ring = StationBreakDarkColors.primary,
                sheet = GuideColors.Dark.sheet,
            )
    }
}

/**
 * A sheet drawn over the guide: its [surface], the 1 dp [line] on its edge toward the screen, and the [ratingBadge]
 * (the content-rating / kids badge) drawn on it. Dark: #141518, #3A3B40, #FFD166. Light: a step above the page, ink at
 * 20%, and a dark amber (a pale yellow vanishes on it). No defaults: a palette states all three.
 */
class SheetColors(
    val surface: Color,
    val line: Color,
    val ratingBadge: Color,
)

/** Dark unless the guide provides its Light choice. Never the system theme. */
val LocalPanelColors = staticCompositionLocalOf { PanelColors.Dark }
