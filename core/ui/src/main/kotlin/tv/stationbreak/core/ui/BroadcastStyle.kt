// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em

/** The on-air look of the live channel (snow, cable banner, test-card slates), taken from the app icon art. */
object BroadcastColors {
    val Ground = BrandColors.Grey
    val Ink = BrandColors.Ink
    val Accent = BrandColors.Orange

    /** Banner text on [Ink]. */
    val Cream = BrandColors.Cream
}

/** Archivo Black for display lines and Space Mono Bold for channel numbers and times (both SIL OFL 1.1). */
object BroadcastType {
    val Display = FontFamily(Font(R.font.archivo_black))
    val Mono = FontFamily(Font(R.font.space_mono_bold, FontWeight.Bold))
}

/**
 * The teletype (DESIGN.md): Space Mono Bold, tracked 0.13em, for flags such as NOW. A font choice only: the colour
 * comes from the caller, which also sets the text in capitals.
 */
fun teletype(size: TextUnit) =
    TextStyle(
        fontFamily = BroadcastType.Mono,
        fontWeight = FontWeight.Bold,
        fontSize = size,
        lineHeight = size,
        letterSpacing = TELETYPE_TRACKING.em,
    )

private const val TELETYPE_TRACKING = 0.13
