// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import tv.stationbreak.core.model.ChannelId
import kotlin.time.Duration.Companion.minutes

private val MINUTE_MS = 1.minutes.inWholeMilliseconds
private val VIEWPORT_SPAN_MS = 90.minutes.inWholeMilliseconds

/**
 * Where focus goes when this model replaces the one [old] was in. The focus itself if its cell is still there; else
 * the cell of the same row that now holds its time (its anchor, never before the origin), else the one Up and Down
 * would pick; else, when the row has no cells left, where the guide would open for [current]. With no [old], that too.
 */
internal fun GuideModel.focusAfterRebuild(
    old: GuideFocus?,
    current: ChannelId?,
    nowMs: Long,
): GuideFocus? {
    if (old == null) return initialFocus(current, nowMs)
    return if (indexOf(old) != null) old else replacementFor(old, nowMs)
}

private fun GuideModel.replacementFor(
    old: GuideFocus,
    nowMs: Long,
): GuideFocus? {
    val cells = rows.firstOrNull { it.channel == old.channel }?.cells.orEmpty()
    val anchor = maxOf(old.anchorMs, originMs)
    val cell =
        cells.firstOrNull { anchor >= it.startMs && anchor < it.endMs }
            ?: cells.lastOrNull { it.startMs <= anchor }
            ?: cells.lastOrNull()
    return if (cell == null) initialFocus(old.channel, nowMs) else GuideFocus(old.channel, cell.startMs, anchor)
}

/** The first row to show with [focus] focused, from [firstVisibleRow]; with none on the grid, it only stays valid. */
internal fun GuideState.Ready.firstRowFor(
    focus: GuideFocus?,
    firstVisibleRow: Int,
): Int {
    val count = model.rows.size
    val row = focus?.let { model.indexOf(it)?.first }
    return if (row == null) {
        firstVisibleRow.coerceIn(0, maxOf(0, count - GUIDE_VISIBLE_ROWS))
    } else {
        firstVisibleRowFor(row, firstVisibleRow, GUIDE_VISIBLE_ROWS, count)
    }
}

/**
 * How long until the clock next needs to move: the next whole minute, or the end of the nearest block on the visible
 * time range if that comes first, so a program ending is seen when it ends. At least 1 ms. Null state: the next minute.
 */
internal fun GuideState.Ready?.nextTickDelayMs(nowMs: Long): Long {
    val untilMinute = MINUTE_MS - nowMs.mod(MINUTE_MS)
    if (this == null) return untilMinute
    val to = viewportStartMs + VIEWPORT_SPAN_MS
    val nextEnd =
        model.rows
            .asSequence()
            .flatMap { it.cells.asSequence() }
            .filter { it.endMs > nowMs && it.startMs < to }
            .minOfOrNull { it.endMs - nowMs }
    return maxOf(1L, minOf(untilMinute, nextEnd ?: untilMinute))
}
