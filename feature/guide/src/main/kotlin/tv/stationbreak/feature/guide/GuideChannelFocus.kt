// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

/** Whether [focus] is on the first cell of its row: Left cannot go past it on the grid, so it goes to the channel. */
internal fun GuideModel.isEarliestCell(focus: GuideFocus): Boolean = indexOf(focus)?.second == 0

/** The row the snapshot's focus names, or null when nothing is focused or the model is not a grid. */
internal fun GuideSnapshot.focusedRow(): GuideRow? {
    val model = (state as? GuideState.Ready)?.model ?: return null
    return focus?.let { model.indexOf(it) }?.let { model.rows[it.first] }
}
