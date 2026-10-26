// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import tv.stationbreak.core.model.ChannelId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

enum class GuideDirection { Up, Down, Left, Right }

/**
 * The focused cell, by identity: its channel and start, not its place in the lists, so a focus that outlives a
 * rebuilt model highlights nothing rather than the wrong cell. [anchorMs] is the time Up and Down keep.
 */
data class GuideFocus(
    val channel: ChannelId,
    val cellStartMs: Long,
    val anchorMs: Long,
)

/** The (row, cell) the [focus] names, or null when that cell is gone. */
fun GuideModel.indexOf(focus: GuideFocus): Pair<Int, Int>? {
    val row = rows.indexOfFirst { it.channel == focus.channel }
    if (row < 0) return null
    val cell = rows[row].cells.indexOfFirst { it.startMs == focus.cellStartMs }
    return if (cell < 0) null else row to cell
}

private fun GuideModel.focusOn(
    row: GuideRow,
    cell: GuideCell,
    anchorMs: Long,
) = GuideFocus(row.channel, cell.startMs, maxOf(anchorMs, originMs))

/**
 * Where the guide opens: the cell on air now on the [current] channel, or on the first row when that channel is not
 * in the model. An empty model gives a focus that names nothing.
 */
fun GuideModel.initialFocus(
    current: ChannelId?,
    nowMs: Long,
): GuideFocus {
    val row = rows.firstOrNull { it.channel == current } ?: rows.firstOrNull()
    val cells = row?.cells.orEmpty()
    val cell = cells.firstOrNull { it.startMs <= nowMs && nowMs < it.endMs } ?: cells.firstOrNull()
    return if (row == null || cell == null) {
        GuideFocus(current ?: ChannelId("none"), originMs, originMs)
    } else {
        focusOn(row, cell, cell.startMs)
    }
}

/** One step of [direction] from [focus]; at an edge, or when the focused cell is gone, the focus stays. */
fun GuideModel.move(
    focus: GuideFocus,
    direction: GuideDirection,
): GuideFocus {
    val (rowIndex, cellIndex) = indexOf(focus) ?: return focus
    val row = rows[rowIndex]
    return when (direction) {
        GuideDirection.Left -> row.cells.getOrNull(cellIndex - 1)?.let { focusOn(row, it, it.startMs) } ?: focus
        GuideDirection.Right -> row.cells.getOrNull(cellIndex + 1)?.let { focusOn(row, it, it.startMs) } ?: focus
        GuideDirection.Up -> moveVertically(focus, rowIndex - 1)
        GuideDirection.Down -> moveVertically(focus, rowIndex + 1)
    }
}

private fun GuideModel.moveVertically(
    focus: GuideFocus,
    targetIndex: Int,
): GuideFocus {
    val target = rows.getOrNull(targetIndex)
    val anchor = maxOf(focus.anchorMs, originMs)
    val cell =
        target?.cells?.let { cells ->
            cells.firstOrNull { anchor >= it.startMs && anchor < it.endMs }
                ?: cells.lastOrNull { it.startMs <= anchor }
                ?: cells.lastOrNull()
        }
    return if (target == null || cell == null) focus else focusOn(target, cell, anchor)
}

/**
 * The start of the visible time range after focus moves: whole half-hour steps from the origin, just enough to keep
 * the focused cell's start inside `[viewport, viewport + span)`, and never before the origin.
 */
fun viewportStartFor(
    focus: GuideFocus,
    model: GuideModel,
    viewportStartMs: Long,
    viewportSpanMs: Duration = 90.minutes,
): Long {
    val origin = model.originMs
    val span = viewportSpanMs.inWholeMilliseconds
    val cellStart = maxOf(focus.cellStartMs, origin)
    val start = maxOf(viewportStartMs, origin)
    return when {
        cellStart < start -> origin + (cellStart - origin).floorDiv(SLOT_MS) * SLOT_MS
        cellStart >= start + span -> start + ((cellStart - span - start).floorDiv(SLOT_MS) + 1) * SLOT_MS
        else -> start
    }
}

/** The first row to show once [focusRow] is focused: it stays visible, scrolling a row at a time at the edges. */
fun firstVisibleRowFor(
    focusRow: Int,
    firstVisibleRow: Int,
    visibleRows: Int,
    rowCount: Int,
): Int {
    val last = maxOf(0, rowCount - visibleRows)
    val first =
        when {
            focusRow < firstVisibleRow -> focusRow
            focusRow >= firstVisibleRow + visibleRows -> focusRow - visibleRows + 1
            else -> firstVisibleRow
        }
    return first.coerceIn(0, last)
}
