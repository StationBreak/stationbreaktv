// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui.mark

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.stationbreak.feature.guide.ui.guideText
import kotlin.math.roundToInt

private val MarkSize: Dp = 32.dp
private val BorderWidth: Dp = 1.5.dp
private val TickThickness: Dp = 2.dp
private val TickInset: Dp = 4.dp
private const val CORNER_FRACTION = 0.16f
private const val TICK_FRACTION = 0.12f

/**
 * One 32 dp channel mark: the channel's logo if it is in the [LocalMarkCache], else an initials tile in the band's
 * colours. The cache is read while drawing, so a logo that arrives redraws only this mark and recomposes nothing.
 * Decorative: the channel name is always read from the row's own text.
 */
@Composable
internal fun ChannelMark(
    markKey: String?,
    initials: String,
    band: MarkBand,
    modifier: Modifier = Modifier,
) {
    val cache = LocalMarkCache.current
    val arrivals = cache?.arrivals?.collectAsState()
    val style = markTileStyle(band)
    val measurer = rememberTextMeasurer()
    val label =
        remember(initials, measurer) {
            measurer.measure(initials, guideText(16.sp, FontWeight.Bold), softWrap = false, maxLines = 1)
        }
    Canvas(modifier.size(MarkSize).clearAndSetSemantics {}) {
        arrivals?.value // Reading the count here makes an arrival redraw this mark.
        when (val visual = markVisual(markKey, cache)) {
            is MarkVisual.Logo -> {
                val px = size.width.roundToInt()
                drawImage(visual.image, dstSize = IntSize(px, px), filterQuality = FilterQuality.Low)
            }

            MarkVisual.Initials -> {
                val side = size.width
                val radius = CornerRadius(side * CORNER_FRACTION)
                drawRoundRect(style.tile, cornerRadius = radius)
                val stroke = BorderWidth.toPx()
                drawRoundRect(
                    style.border,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(side - stroke, side - stroke),
                    cornerRadius = radius,
                    style = Stroke(stroke),
                )
                drawRoundRect(
                    style.tick,
                    topLeft = Offset(TickInset.toPx(), TickInset.toPx()),
                    size = Size(side * TICK_FRACTION, TickThickness.toPx()),
                    cornerRadius = CornerRadius(TickThickness.toPx() / 2),
                )
                drawText(
                    label,
                    color = style.initials,
                    topLeft = Offset((side - label.size.width) / 2, (side - label.size.height) / 2),
                )
            }
        }
    }
}
