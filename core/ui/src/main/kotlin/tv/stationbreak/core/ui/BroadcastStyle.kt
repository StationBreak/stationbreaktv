// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

/** The on-air look of the live channel (snow, cable banner, test-card slates), taken from the app icon art. */
object BroadcastColors {
    val Ground = Color(0xFFCFCBC2)
    val Ink = Color(0xFF1C1B19)
    val Accent = Color(0xFFE8642C)

    /** Banner text on [Ink]. */
    val Cream = Color(0xFFF3EEE3)
}

/** Archivo Black for display lines and Space Mono Bold for channel numbers and times (both SIL OFL 1.1). */
object BroadcastType {
    val Display = FontFamily(Font(R.font.archivo_black))
    val Mono = FontFamily(Font(R.font.space_mono_bold))
}
