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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.stationbreak.feature.guide.ui.LocalGuideColors
import tv.stationbreak.feature.guide.ui.guideText
import kotlin.math.roundToInt

/** What the mark draws now, for tests: they cannot see pixels without API 26. A custom key, so never announced. */
internal val MarkKindKey = SemanticsPropertyKey<MarkKind>("MarkKind")
private var SemanticsPropertyReceiver.markKind by MarkKindKey

private val MarkBoxWidth: Dp = 50.dp
private val MarkBoxHeight: Dp = 28.dp

/**
 * One channel mark in a 50 by 28 dp box: the channel's logo if it is in the [LocalMarkCache], drawn as it is (no tint,
 * tile, border or shadow) and scaled to fit the box, centred; else the channel's initials, bold and centred in the
 * same box, with no tile. The cache is read while drawing, so a logo that arrives redraws only this mark and
 * recomposes nothing. Decorative: the channel name is read from the panel.
 */
@Composable
internal fun ChannelMark(
    markKey: String?,
    initials: String,
    modifier: Modifier = Modifier,
) {
    val cache = LocalMarkCache.current
    val arrivals = cache?.arrivals?.collectAsState()
    val color = LocalGuideColors.current.textPrimary
    val measurer = LocalMarkTextMeasurer.current ?: rememberTextMeasurer()
    val label =
        remember(initials, measurer) {
            measurer.measure(initials, guideText(12.sp, FontWeight.Bold), softWrap = false, maxLines = 1)
        }
    Canvas(
        modifier.size(MarkBoxWidth, MarkBoxHeight).clearAndSetSemantics {
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
            val scale = minOf(size.width / image.width, size.height / image.height)
            val w = (image.width * scale).roundToInt()
            val h = (image.height * scale).roundToInt()
            drawImage(
                image,
                dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
                dstSize = IntSize(w, h),
                filterQuality = FilterQuality.Low,
            )
        } else {
            drawText(
                label,
                color = color,
                topLeft = Offset((size.width - label.size.width) / 2, (size.height - label.size.height) / 2),
            )
        }
    }
}

/** The one text measurer the guide's rows share; null where a mark is drawn alone, which then makes its own. */
internal val LocalMarkTextMeasurer = staticCompositionLocalOf<TextMeasurer?> { null }
