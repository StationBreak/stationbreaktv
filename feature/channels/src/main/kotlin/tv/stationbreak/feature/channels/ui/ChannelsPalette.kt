// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import tv.stationbreak.core.ui.BroadcastColors
import tv.stationbreak.core.ui.StationBreakDarkColors

/** How much of a dimmed (unavailable) row's name shows through; its reason line stays at full strength. */
internal const val DIMMED_ALPHA = 0.7f

/**
 * The text colours of the channel screen, all drawn on [BroadcastColors.Ink]. The screen reads these, and
 * `ChannelsPaletteTest` checks every one in [pairs] against WCAG 4.5:1, so the two cannot drift.
 */
internal object ChannelsPalette {
    val ground = BroadcastColors.Ink

    /** Titles, names, panel text, choices and picker digits. The screen's default content colour. */
    val text = BroadcastColors.Cream

    /** Headings, the read-only note, message lines and reasons. */
    val secondary = StationBreakDarkColors.onSurfaceVariant

    /** A dimmed row's name: [text] at [DIMMED_ALPHA] as it lands on [ground]. */
    val dimmed = text.copy(alpha = DIMMED_ALPHA).compositeOver(ground)

    /** Every text colour with the background it is drawn on, named for the failure message. */
    val pairs: List<Triple<String, Color, Color>> =
        listOf(
            Triple("text on Ink", text, ground),
            Triple("secondary on Ink", secondary, ground),
            Triple("dimmed row on Ink", dimmed, ground),
        )
}
