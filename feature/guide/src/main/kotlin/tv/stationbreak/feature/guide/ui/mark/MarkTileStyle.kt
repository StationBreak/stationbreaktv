// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui.mark

import androidx.compose.ui.graphics.Color
import tv.stationbreak.feature.guide.MarkBand

/** Colours of one initials tile. The accent appears once per tile and never fills it. */
internal data class MarkTileStyle(
    val tile: Color,
    val border: Color,
    val initials: Color,
    val tick: Color,
)

private val Ink = Color(0xFF1C1B19)
private val Cream = Color(0xFFF3EEE3)
private val Accent = Color(0xFFE8642C)
private val WarmGrey = Color(0xFF5A554C)

private val CreamTile = MarkTileStyle(tile = Cream, border = Ink, initials = Ink, tick = Accent)
private val InkTile = MarkTileStyle(tile = Ink, border = WarmGrey, initials = Cream, tick = Accent)
private val PremiumTile = MarkTileStyle(tile = Ink, border = Ink, initials = Accent, tick = Cream)

/** The tile treatment per band, from the ChannelMarks "Band to tile treatment" table. Same on both Guide themes. */
internal fun markTileStyle(band: MarkBand): MarkTileStyle =
    when (band) {
        MarkBand.Kids, MarkBand.General, MarkBand.Custom -> CreamTile
        MarkBand.Drama, MarkBand.Genre, MarkBand.World -> InkTile
        MarkBand.Premium -> PremiumTile
    }
