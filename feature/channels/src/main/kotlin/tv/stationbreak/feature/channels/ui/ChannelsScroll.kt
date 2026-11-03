// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos

/**
 * Scrolls only when line [index] is not fully on screen: up so that the line above it (its heading, when it is the
 * first of a group) shows, down so that it is the last line shown. A move inside the screen scrolls nothing.
 */
internal suspend fun LazyListState.keepVisible(index: Int) {
    val info = layoutInfo
    val shown =
        info.visibleItemsInfo.filter {
            it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset
        }
    when {
        shown.isEmpty() -> scrollToItem(index)
        index < shown.first().index -> scrollToItem((index - 1).coerceAtLeast(0))
        index > shown.last().index -> scrollToItem((index - shown.size + 1).coerceAtLeast(0))
        else -> return
    }
    nudgeIntoView(index)
}

/**
 * Rows are not all one height, so the estimate above can leave the line a little clipped: once it is laid out, scroll
 * by the clipped amount.
 */
private suspend fun LazyListState.nudgeIntoView(index: Int) {
    // A few passes: lines taller than the estimate can leave the line wholly off screen (it is not laid out at all),
    // and then the pass moves half a screen towards it, and the next one places it exactly.
    repeat(NUDGE_PASSES) {
        withFrameNanos { }
        val info = layoutInfo
        val shown = info.visibleItemsInfo
        val item = shown.firstOrNull { it.index == index }
        val half = (info.viewportEndOffset - info.viewportStartOffset) / 2f
        when {
            item == null -> {
                val last = shown.lastOrNull() ?: return
                if (index > last.index) scrollBy(half) else scrollBy(-half)
            }

            item.offset + item.size > info.viewportEndOffset -> {
                scrollBy((item.offset + item.size - info.viewportEndOffset).toFloat())
            }

            item.offset < info.viewportStartOffset -> {
                scrollBy((item.offset - info.viewportStartOffset).toFloat())
            }

            else -> {
                return
            }
        }
    }
}

private const val NUDGE_PASSES = 6
