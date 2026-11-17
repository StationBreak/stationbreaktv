// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

// How long a hint pair stays when the row is too narrow for all of them: the highest number goes first.
internal const val HINT_RANK_BACK = 0
internal const val HINT_RANK_OK = 1
internal const val HINT_RANK_LEFT = 2
internal const val HINT_RANK_MENU = 3

/** The ranks of the pairs the hint row shows, in display order: OK, Left, Back, Menu, each only when it shows. */
internal fun hintRanks(
    hasOk: Boolean,
    hasLeft: Boolean,
    hasMenu: Boolean,
): List<Int> =
    buildList {
        if (hasOk) add(HINT_RANK_OK)
        if (hasLeft) add(HINT_RANK_LEFT)
        add(HINT_RANK_BACK)
        if (hasMenu) add(HINT_RANK_MENU)
    }

/**
 * Which of the hint pairs fit in [available] px: [widths] and [ranks] are in display order, [gap] is the space between
 * two shown pairs. Pairs are kept in rank order (lowest first) while they fit, and the first that does not, with every
 * pair after it in rank order, is dropped whole: a half-hint, or a later pair slipping in past a dropped one, would
 * read as a different row. The first-ranked pair is always kept (it is cut with an ellipsis if it alone is too wide).
 */
internal fun visibleHints(
    widths: List<Int>,
    ranks: List<Int>,
    gap: Int,
    available: Int,
): List<Boolean> {
    val shown = MutableList(widths.size) { false }
    var used = 0
    var count = 0
    for (i in widths.indices.sortedBy { ranks[it] }) {
        val next = used + widths[i] + if (count > 0) gap else 0
        if (count > 0 && next > available) break
        shown[i] = true
        used = next
        count++
    }
    return shown
}

/**
 * A row of hint pairs that drops whole pairs, lowest-ranked first (see [visibleHints]), instead of clipping the last
 * one at the edge. Each child is one pair; [ranks] has one entry per child, in order.
 */
@Composable
internal fun HintFlow(
    ranks: List<Int>,
    gap: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val loose = Constraints(maxHeight = constraints.maxHeight)
        val natural = measurables.map { it.measure(loose) }
        val shown = visibleHints(natural.map { it.width }, ranks, gapPx, constraints.maxWidth)
        val placeables =
            natural.mapIndexedNotNull { i, placeable ->
                when {
                    !shown[i] -> {
                        null
                    }

                    placeable.width > constraints.maxWidth -> {
                        measurables[i].measure(loose.copy(maxWidth = constraints.maxWidth))
                    }

                    else -> {
                        placeable
                    }
                }
            }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        val rowHeight = maxOf(height, constraints.minHeight)
        layout(constraints.maxWidth, rowHeight) {
            var x = 0
            placeables.forEach {
                it.place(x, Alignment.CenterVertically.align(it.height, rowHeight))
                x += it.width + gapPx
            }
        }
    }
}
