// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colours of a panel that is drawn over the guide (Settings, Libraries): its ground, its text, its quieter text
 * and its focus ring. The guide provides these from its own Dark or Light tokens through [LocalPanelColors], so a
 * panel follows the guide's appearance without a feature depending on the guide. Inside the guide the panel takes the
 * guide's tokens in both themes, so Dark there is the guide's own (its page, cream text, orange ring). [Dark] here is
 * only the default for a panel shown with no guide around it (the same warm ink, cream, muted and orange). Text colours
 * may carry an alpha; contrast is judged on the composite over [ground]. [sheet] is the sheet over the guide; text on
 * its surface is [text] and [secondary]. [keyCapStroke] outlines a hint's key cap, and [flag] is a section flag's
 * text (DESIGN.md's grey in Dark). [band] is the number bands' accents. The core:ui sheet pieces (`LeftSheet`,
 * `KeyCap`, `KeyHintRow`) read only these.
 */
@Suppress("LongParameterList") // One field per panel colour role.
class PanelColors(
    val ground: Color,
    val text: Color,
    val secondary: Color,
    val ring: Color,
    val sheet: SheetColors,
    val keyCapStroke: Color,
    val flag: Color,
    val band: BandColors,
) {
    companion object {
        val Dark =
            PanelColors(
                ground = BrandColors.Ink,
                text = BrandColors.Cream,
                secondary = BrandColors.Muted,
                ring = BrandColors.Orange,
                sheet = DarkSheet,
                keyCapStroke = BrandColors.Grey,
                flag = BrandColors.Grey,
                band = BandColors.Dark,
            )
    }
}

/**
 * A sheet drawn over the guide: its [surface], the 1 dp [line] on its edge toward the screen, and the [ratingBadge]
 * (the content-rating / kids badge) drawn on it. Dark: #262320, #3D3832, #FFD166. Light: a step above the page, ink at
 * 20%, and a dark amber (a pale yellow vanishes on it). No defaults: a palette states all three.
 */
class SheetColors(
    val surface: Color,
    val line: Color,
    val ratingBadge: Color,
)

/** Dark unless the guide provides its Light choice. Never the system theme. */
val LocalPanelColors = staticCompositionLocalOf { PanelColors.Dark }
