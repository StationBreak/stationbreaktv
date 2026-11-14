// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.stationbreak.feature.guide.CellKind
import tv.stationbreak.feature.guide.GuideCell
import tv.stationbreak.feature.guide.R

/** What a cell writes on the grid ([title]) and what a screen reader hears of it ([described]). */
internal class CellLabels(
    val title: String,
    val described: String,
)

/**
 * [cell]'s labels. All the strings are always read, so a cell that changes kind does not change which composables run.
 * A slate on the grid is the "OFF AIR" plate and a break the "STATION BREAK" plate; the panel keeps [cellTitle]'s
 * longer lines. A screen reader hears a break's panel line ("Station break · Up next at 9:30 PM"), not the plate.
 */
@Composable
internal fun cellLabels(cell: GuideCell): CellLabels {
    val spoken = cellTitle(cell, programCopy())
    val offAir = stringResource(R.string.guide_off_air)
    val stationBreak = stringResource(R.string.guide_station_break)
    val title =
        when (cell.kind) {
            CellKind.Slate -> offAir.uppercase()
            CellKind.Break -> stationBreak.uppercase()
            CellKind.Program, CellKind.Unavailable -> spoken
        }
    return CellLabels(title, if (cell.kind == CellKind.Break) spoken else title)
}

/** A slate and a station break are plates on the grid: the slate's fill and face, no progress line, no second line. */
internal fun CellKind.isPlate(): Boolean = this == CellKind.Slate || this == CellKind.Break
