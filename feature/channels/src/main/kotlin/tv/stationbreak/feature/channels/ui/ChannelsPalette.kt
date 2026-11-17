// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import tv.stationbreak.core.ui.LocalPanelColors
import tv.stationbreak.core.ui.PanelColors
import tv.stationbreak.core.ui.drawnFocus

/** How much of a dimmed (unavailable) row's name shows through; its reason line stays at full strength. */
internal const val DIMMED_ALPHA = 0.7f

private const val SECONDARY_LINE_SP = 16
private const val SECONDARY_LINE_HEIGHT_SP = 24

/**
 * The second line under a channel's name (a reason): the theme's secondary size, 14sp, is under the 10-foot minimum,
 * so 16sp on a 24sp line. The colour is [ChannelsPalette.secondary].
 */
@Composable
internal fun channelsSecondaryLine(): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(fontSize = SECONDARY_LINE_SP.sp, lineHeight = SECONDARY_LINE_HEIGHT_SP.sp)

/**
 * The colours of the channel screens, read from the panel the guide provides ([LocalPanelColors]), so they follow the
 * guide's Dark or Light theme; with no guide around, the panel's default is Ink and Cream, the screens' own. Text is
 * drawn on [ground], and on [sheet] in the properties sheet. `ChannelsPaletteTest` checks every pair in [pairs] for
 * both guide themes and the default against WCAG 4.5:1 (the ring 3:1), so the screen and the check cannot drift.
 */
internal object ChannelsPalette {
    val ground: Color @Composable get() = LocalPanelColors.current.ground

    /** Titles, names, panel text, choices and picker digits. The screen's default content colour. */
    val text: Color @Composable get() = LocalPanelColors.current.text

    /** Headings, the read-only note, message lines and reasons. */
    val secondary: Color @Composable get() = LocalPanelColors.current.secondary

    /** The focused row's ring: the guide's focus colour. */
    val ring: Color @Composable get() = LocalPanelColors.current.ring

    /** The properties sheet's ground. */
    val sheet: Color @Composable get() = LocalPanelColors.current.sheet.surface

    /** The 1 dp line on the properties sheet's left edge. */
    val sheetLine: Color @Composable get() = LocalPanelColors.current.sheet.line

    /** The content-rating badge on the sheet. */
    val ratingBadge: Color @Composable get() = LocalPanelColors.current.sheet.ratingBadge

    /** A dimmed row's name: the panel's text at [DIMMED_ALPHA] of its own strength. */
    val dimmed: Color @Composable get() = dimmed(LocalPanelColors.current)

    /** [panel]'s text at [DIMMED_ALPHA] of its own alpha, as it lands on its ground. */
    fun dimmed(panel: PanelColors): Color {
        val faded = panel.text.copy(alpha = panel.text.alpha * DIMMED_ALPHA)
        return faded.compositeOver(panel.ground)
    }

    /** Every text colour of [panel] with the background it is drawn on, named for the failure message. */
    fun pairs(panel: PanelColors): List<Triple<String, Color, Color>> =
        listOf(
            Triple("text on the ground", panel.text, panel.ground),
            Triple("secondary on the ground", panel.secondary, panel.ground),
            Triple("dimmed row on the ground", dimmed(panel), panel.ground),
            Triple("text on the sheet", panel.text, panel.sheet.surface),
            Triple("secondary on the sheet", panel.secondary, panel.sheet.surface),
            Triple("rating badge on the sheet", panel.sheet.ratingBadge, panel.sheet.surface),
        )
}

/**
 * A row's focus on the channel screens, the guide's way: the guide's focus colour as a ring and no scale. Drawn from
 * the screen's own state, as the rows have no Compose focus target ([drawnFocus]).
 */
@Composable
internal fun Modifier.channelFocus(focused: Boolean): Modifier {
    val ring = ChannelsPalette.ring
    return drawnFocus(focused, ring = ring, scaled = false)
}
