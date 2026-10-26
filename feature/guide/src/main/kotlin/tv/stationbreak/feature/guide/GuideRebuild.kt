// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import tv.stationbreak.core.model.ChannelId
import kotlin.time.Duration.Companion.minutes

private val MINUTE_MS = 1.minutes.inWholeMilliseconds
private val VIEWPORT_SPAN_MS = GUIDE_VIEWPORT_SPAN.inWholeMilliseconds

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

/**
 * The focus once the clock has moved from [previousNowMs] to [nowMs]: one on a program that was on air then and has
 * ended since goes to what is on now in the same row, as the picture moved on; any other focus stays where it is.
 */
internal fun GuideModel.followingNow(
    focus: GuideFocus,
    previousNowMs: Long?,
    nowMs: Long,
): GuideFocus {
    val (r, c) = indexOf(focus) ?: return focus
    val row = rows[r]
    val cell = row.cells[c]
    val wasOn = previousNowMs != null && cell.startMs <= previousNowMs && previousNowMs < cell.endMs
    if (!wasOn || cell.endMs > nowMs) return focus
    val onNow = row.cells.firstOrNull { it.startMs <= nowMs && nowMs < it.endMs } ?: return focus
    return GuideFocus(row.channel, onNow.startMs, maxOf(onNow.startMs, originMs))
}

/**
 * The first row to show when the guide opens on [focus]: its row at the top, moved up only as far as it takes to fill
 * the last page. The first row with no focus.
 */
internal fun GuideState.Ready.openingRowFor(focus: GuideFocus?): Int {
    val row = focus?.let { model.indexOf(it)?.first } ?: 0
    return row.coerceIn(0, maxOf(0, model.rows.size - GUIDE_VISIBLE_ROWS))
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
