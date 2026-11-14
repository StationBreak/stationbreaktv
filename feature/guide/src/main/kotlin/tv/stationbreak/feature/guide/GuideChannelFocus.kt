// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

/**
 * Whether [cell] is a break too narrow to be a focus stop: drawn on the grid as less than [narrowBelowMs] of time (from
 * the origin, as the grid draws it), the time the screen's unlabelled width (`CompactBelow`) stands for. 0 (the
 * default) makes every break a stop.
 */
internal fun GuideModel.isNarrowBreak(
    cell: GuideCell,
    narrowBelowMs: Long,
): Boolean = cell.kind == CellKind.Break && cell.endMs - maxOf(cell.startMs, originMs) < narrowBelowMs

/**
 * Whether [focus] is on the first stop of its row: the first cell, or one with only narrow breaks ([isNarrowBreak])
 * before it. Left cannot go past it on the grid, so it goes to the channel.
 */
internal fun GuideModel.isEarliestCell(
    focus: GuideFocus,
    narrowBelowMs: Long = 0L,
): Boolean {
    val (row, cell) = indexOf(focus) ?: return false
    return rows[row].cells.subList(0, cell).all { isNarrowBreak(it, narrowBelowMs) }
}

/** The row the snapshot's focus names, or null when nothing is focused or the model is not a grid. */
internal fun GuideSnapshot.focusedRow(): GuideRow? {
    val model = (state as? GuideState.Ready)?.model ?: return null
    return focus?.let { model.indexOf(it) }?.let { model.rows[it.first] }
}
