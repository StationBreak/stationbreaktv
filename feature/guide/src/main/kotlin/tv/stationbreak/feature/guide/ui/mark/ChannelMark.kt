// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui.mark

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.stationbreak.feature.guide.MarkBand
import tv.stationbreak.feature.guide.ui.guideText
import kotlin.math.roundToInt

/** What the mark draws now, for tests: they cannot see pixels without API 26. A custom key, so never announced. */
internal val MarkKindKey = SemanticsPropertyKey<MarkKind>("MarkKind")
private var SemanticsPropertyReceiver.markKind by MarkKindKey

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
    val measurer = LocalMarkTextMeasurer.current ?: rememberTextMeasurer()
    val density = LocalDensity.current
    val borderStroke = remember(density) { Stroke(with(density) { BorderWidth.toPx() }) }
    val label =
        remember(initials, measurer) {
            measurer.measure(initials, guideText(16.sp, FontWeight.Bold), softWrap = false, maxLines = 1)
        }
    Canvas(
        modifier.size(MarkSize).clearAndSetSemantics {
            // Read by the semantics phase, not by composition: an arrival updates this and recomposes nothing. A custom
            // key, so no screen reader reads it.
            arrivals?.value
            markKind = if (markVisual(markKey, cache) is MarkVisual.Logo) MarkKind.Logo else MarkKind.Initials
        },
    ) {
        arrivals?.value // Reading the count here makes an arrival redraw this mark.
        // No MarkVisual is made per draw: the cache is asked directly.
        val image = if (markKey == null) null else cache?.peek(markKey)
        if (image != null) {
            val px = size.width.roundToInt()
            drawImage(image, dstSize = IntSize(px, px), filterQuality = FilterQuality.Low)
        } else {
            drawInitialsTile(style, borderStroke, label)
        }
    }
}

private fun DrawScope.drawInitialsTile(
    style: MarkTileStyle,
    borderStroke: Stroke,
    label: TextLayoutResult,
) {
    val side = size.width
    val radius = CornerRadius(side * CORNER_FRACTION)
    drawRoundRect(style.tile, cornerRadius = radius)
    val stroke = borderStroke.width
    drawRoundRect(
        style.border,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(side - stroke, side - stroke),
        cornerRadius = radius,
        style = borderStroke,
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

/** The one text measurer the guide's rows share; null where a mark is drawn alone, which then makes its own. */
internal val LocalMarkTextMeasurer = staticCompositionLocalOf<TextMeasurer?> { null }
