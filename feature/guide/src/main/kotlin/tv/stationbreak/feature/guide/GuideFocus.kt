// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import tv.stationbreak.core.model.ChannelId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** How much time the grid shows at once: the one span that scrolling and the clock tick both go by. */
internal val GUIDE_VIEWPORT_SPAN: Duration = 90.minutes

internal enum class GuideDirection { Up, Down, Left, Right }

/**
 * The focused cell, by identity: its channel and start, not its place in the lists, so a focus that outlives a
 * rebuilt model highlights nothing rather than the wrong cell. [anchorMs] is the time Up and Down keep.
 */
internal data class GuideFocus(
    val channel: ChannelId,
    val cellStartMs: Long,
    val anchorMs: Long,
)

/** The (row, cell) the [focus] names, or null when that cell is gone. */
internal fun GuideModel.indexOf(focus: GuideFocus): Pair<Int, Int>? {
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
 * Where the guide opens: the cell on air now on the [current] channel's row, or, when that channel is not in the model
 * or has no cells, on the first row that has cells. Null when no row has a cell, since there is nothing to focus.
 */
internal fun GuideModel.initialFocus(
    current: ChannelId?,
    nowMs: Long,
): GuideFocus? {
    val row =
        rows.firstOrNull { it.channel == current && it.cells.isNotEmpty() }
            ?: rows.firstOrNull { it.cells.isNotEmpty() }
    val cells = row?.cells.orEmpty()
    val cell = cells.firstOrNull { it.startMs <= nowMs && nowMs < it.endMs } ?: cells.firstOrNull()
    return if (row == null || cell == null) null else focusOn(row, cell, cell.startMs)
}

/**
 * The cell on air now on the [current] channel, which Back returns to; null when [current] is unknown or not on the
 * grid (the guide then opens on the first row, which is not what is playing).
 */
internal fun GuideSnapshot.playingFocus(current: ChannelId?): GuideFocus? {
    val ready = state as? GuideState.Ready
    return ready?.model?.initialFocus(current, ready.nowMs)?.takeIf { it.channel == current }
}

/** Whether the focus is off the cell [playingFocus] names (cell, not anchor); false when there is nothing playing. */
internal fun GuideSnapshot.isAwayFrom(current: ChannelId?): Boolean {
    val at = focus
    val playing = playingFocus(current)
    return at != null && playing != null && !at.sameCellAs(playing)
}

/** Whether both name the same cell (the anchor, the time Up and Down keep, does not count). */
internal fun GuideFocus.sameCellAs(o: GuideFocus): Boolean = channel == o.channel && cellStartMs == o.cellStartMs

/**
 * One step of [direction] from [focus] at [nowMs] (the moment the grid shows); at the left or right edge of a row, or
 * when the focused cell is gone, the focus stays. Up and Down skip rows that have no cells and wrap, as live TV's
 * flipping does: Down past the last row goes to the first, Up past the first to the last. They stay put when no other
 * row has any cells. They go by the focus's anchor, never earlier than now: an anchor kept from minutes ago would land
 * on a program that has ended since.
 */
internal fun GuideModel.move(
    focus: GuideFocus,
    direction: GuideDirection,
    nowMs: Long,
): GuideFocus {
    val (rowIndex, cellIndex) = indexOf(focus) ?: return focus
    val row = rows[rowIndex]
    return when (direction) {
        GuideDirection.Left -> {
            row.cells.getOrNull(cellIndex - 1)?.let { focusOn(row, it, it.startMs) } ?: focus
        }

        GuideDirection.Right -> {
            row.cells.getOrNull(cellIndex + 1)?.let { focusOn(row, it, it.startMs) } ?: focus
        }

        GuideDirection.Up -> {
            moveVertically(focus, (rowIndex - 1 downTo 0) + (rows.lastIndex downTo rowIndex + 1), nowMs)
        }

        GuideDirection.Down -> {
            moveVertically(focus, (rowIndex + 1 until rows.size) + (0 until rowIndex), nowMs)
        }
    }
}

private fun GuideModel.moveVertically(
    focus: GuideFocus,
    candidates: List<Int>,
    nowMs: Long,
): GuideFocus {
    val target = candidates.firstOrNull { rows[it].cells.isNotEmpty() }?.let { rows[it] }
    val anchor = maxOf(focus.anchorMs, originMs, nowMs)
    val cells = target?.cells.orEmpty()
    val cell =
        cells.firstOrNull { anchor >= it.startMs && anchor < it.endMs }
            ?: cells.lastOrNull { it.startMs <= anchor }
            ?: cells.lastOrNull()
    return if (target == null || cell == null) focus else focusOn(target, cell, anchor)
}

/**
 * The start of the visible time range after focus moves in [direction], always a whole number of half hours from the
 * origin and never before it. [viewportStartMs] is where the range starts now, [viewportSpan] how long it is. A null
 * [direction] is a rebuild (the clock or a new window), not a key: it moves the range as Left and Right do.
 *
 * - Up and Down keep the time columns steady: the range stays put while the focused cell overlaps it (a long cell that
 *   began before the range still counts). Only when the cell does not overlap is the range moved, by the fewest whole
 *   slots that show the focus's anchor time if the cell holds it, else the cell's nearest edge, so the focused cell is
 *   always at least partly visible.
 * - Left and Right (and a rebuild) move the range only when the focused cell is entirely outside it, to the slot that
 *   holds the cell's start.
 */
internal fun viewportStartFor(
    focus: GuideFocus,
    model: GuideModel,
    viewportStartMs: Long,
    direction: GuideDirection?,
    viewportSpan: Duration = GUIDE_VIEWPORT_SPAN,
): Long {
    val origin = model.originMs
    val span = viewportSpan.inWholeMilliseconds
    val start = maxOf(viewportStartMs, origin)
    val cellStart = maxOf(focus.cellStartMs, origin)
    val cell = model.indexOf(focus)?.let { (r, c) -> model.rows[r].cells[c] }
    val cellEnd = maxOf(cell?.endMs ?: (cellStart + 1), cellStart + 1)
    val anchor = maxOf(focus.anchorMs, origin)
    return when {
        cellEnd > start && cellStart < start + span -> {
            start
        }

        direction == null || direction == GuideDirection.Left || direction == GuideDirection.Right -> {
            origin + (cellStart - origin).floorDiv(SLOT_MS) * SLOT_MS
        }

        // Up or Down: the anchor when the cell holds it, else the nearest edge of the cell, so the cell is seen.
        cellStart <= anchor && anchor < cellEnd -> {
            revealing(anchor, origin, start, span)
        }

        cellEnd <= start -> {
            revealing(maxOf(cellEnd - 1, origin), origin, start, span)
        }

        else -> {
            revealing(cellStart, origin, start, span)
        }
    }
}

/** The fewest whole slots from [origin] to move [start] so that [timeMs] falls inside `[start, start + span)`. */
private fun revealing(
    timeMs: Long,
    origin: Long,
    start: Long,
    span: Long,
): Long =
    when {
        timeMs < start -> origin + (timeMs - origin).floorDiv(SLOT_MS) * SLOT_MS
        timeMs >= start + span -> start + ((timeMs - span - start).floorDiv(SLOT_MS) + 1) * SLOT_MS
        else -> start
    }

/** The first row to show once [focusRow] is focused: it stays visible, scrolling a row at a time at the edges. */
internal fun firstVisibleRowFor(
    focusRow: Int,
    firstVisibleRow: Int,
    visibleRows: Int,
    rowCount: Int,
): Int {
    require(visibleRows > 0) { "At least one row must be visible" }
    val last = maxOf(0, rowCount - visibleRows)
    val first =
        when {
            focusRow < firstVisibleRow -> focusRow
            focusRow >= firstVisibleRow + visibleRows -> focusRow - visibleRows + 1
            else -> firstVisibleRow
        }
    return first.coerceIn(0, last)
}
