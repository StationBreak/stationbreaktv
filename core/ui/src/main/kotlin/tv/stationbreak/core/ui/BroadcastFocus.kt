// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * The one on-air focus treatment (Look, 2026-11-04): an Accent fill, Ink content, and a Cream ring drawn outside the
 * fill. Cream on Accent is under 3:1, so a [ringGap] of Ink sits between them and the ring is read against the page
 * ([gap], the same Ink), which `ContrastTest` checks. Grid cells stay at [CELL_SCALE] so a move does not reflow the
 * grid; buttons use [BUTTON_SCALE]. A resting button is a [restingOutlineWidth] Ground outline on the Ink page, with
 * [restingContent] text.
 *
 * Colours are the provisional broadcast palette, not a brand.
 */
object BroadcastFocus {
    val fill = BroadcastColors.Accent
    val content = BroadcastColors.Ink
    val ring = BroadcastColors.Cream

    /** The band between the ring and the fill, and the page the ring is measured against. */
    val gap = BroadcastColors.Ink

    val ringWidth = 3.dp
    val ringGap = 2.dp

    const val CELL_SCALE = 1f
    const val BUTTON_SCALE = 1.04f

    val restingOutline = BroadcastColors.Ground
    val restingOutlineWidth = 2.dp
    val restingContent = BroadcastColors.Cream
}

/**
 * Paints [BroadcastFocus] into [width] by [height] at [left], [top]: the Cream ring occupies the outer [ringWidth],
 * then [ringGap] of Ink, then the Accent fill. The stroke is inset by half its width so it stays inside the rect and
 * a clip at the rect's edge cannot cut it. A rect too small for the ring is filled with the ring colour, so a sliver
 * of a cell is still visibly focused.
 */
fun DrawScope.drawBroadcastFocus(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
) {
    val ringPx = BroadcastFocus.ringWidth.toPx()
    val gapPx = BroadcastFocus.ringGap.toPx()
    if (width <= ringPx * 2f || height <= ringPx * 2f) {
        drawRect(BroadcastFocus.ring, Offset(left, top), Size(width, height))
        return
    }
    val inner = ringPx + gapPx
    drawRect(
        BroadcastFocus.gap,
        Offset(left + ringPx, top + ringPx),
        Size(width - ringPx * 2f, height - ringPx * 2f),
    )
    if (width > inner * 2f && height > inner * 2f) {
        drawRect(
            BroadcastFocus.fill,
            Offset(left + inner, top + inner),
            Size(width - inner * 2f, height - inner * 2f),
        )
    }
    drawRect(
        BroadcastFocus.ring,
        Offset(left + ringPx / 2f, top + ringPx / 2f),
        Size(width - ringPx, height - ringPx),
        style = Stroke(ringPx),
    )
}

/** The resting button: a Ground outline on the Ink page, with no fill of its own. */
fun DrawScope.drawRestingOutline(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
) {
    val stroke = BroadcastFocus.restingOutlineWidth.toPx()
    if (width <= stroke || height <= stroke) return
    drawRect(
        BroadcastFocus.restingOutline,
        Offset(left + stroke / 2f, top + stroke / 2f),
        Size(width - stroke, height - stroke),
        style = Stroke(stroke),
    )
}
