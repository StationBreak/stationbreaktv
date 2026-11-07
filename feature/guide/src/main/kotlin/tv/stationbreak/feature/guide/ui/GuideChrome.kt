// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.graphics.BlurMaskFilter
import android.provider.Settings
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The focused card grows by this much past its resting bounds, in the draw only: 3 by 2 reference pixels at the Guide A
 * conversion of 1.5 reference pixels per dp. The title is not scaled. Content scale stays at [CONTENT_SCALE].
 */
internal val GuideFocusExtendX = 2.dp
internal val GuideFocusExtendY = (2f / 1.5f).dp

/** Programme radius, 18 reference pixels. */
internal val GuideCardRadius = 12.dp

/**
 * Extra inset inside the cell's own 1 dp padding, so the focus extension stays inside the cell and the neighbour's
 * card is not covered. The cell's measured width does not change.
 */
internal val GuideCardInset = 2.dp

/** A focused programme does not scale. */
internal const val CONTENT_SCALE = 1f

private const val FOCUS_MOTION_MS = 160
private val ShadowOffset = 4.dp
private val ShadowBlur = 8.dp
private const val RESTING_HIGHLIGHT_ALPHA = 0.25f
private val RestingHighlightWidth = 1.dp

/** 160 ms ease-out, or immediate when the TV's animator scale is off. */
@Composable
internal fun guideFocusMotion(): FiniteAnimationSpec<Float> =
    if (guideMotionReduced()) snap() else tween(durationMillis = FOCUS_MOTION_MS, easing = EaseOut)

@Composable
private fun guideMotionReduced(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

/** How far a focused card grows, in pixels, and how far along that growth [amount] is (0 at rest, 1 focused). */
internal data class FocusExtent(
    val inset: Float,
    val extendX: Float,
    val extendY: Float,
    val amount: Float,
)

/**
 * Where the card is drawn inside a cell whose draw size is [width] by [height]. At amount 0 the rect is the resting
 * card. At 1 it has grown by the extension on every side, still inside the cell when that extension is no larger than
 * the inset.
 */
internal fun guideFocusBounds(
    width: Float,
    height: Float,
    extent: FocusExtent,
): Rect {
    val ex = extent.extendX * extent.amount
    val ey = extent.extendY * extent.amount
    val left = extent.inset - ex
    val top = extent.inset - ey
    return Rect(
        left = left,
        top = top,
        right = width - extent.inset + ex,
        bottom = height - extent.inset + ey,
    )
}

/**
 * The programme card. [amount] is 0 at rest and 1 when focused: the fill moves from [resting] to the focus fill, and
 * the rounded surface extends. One shadow sits under it while it is focused. There is no ring, gap or second stroke.
 */
internal fun DrawScope.drawGuideCard(
    colors: GuideColors,
    resting: Color,
    amount: Float,
) {
    val bounds =
        guideFocusBounds(
            size.width,
            size.height,
            FocusExtent(
                inset = GuideCardInset.toPx(),
                extendX = GuideFocusExtendX.toPx(),
                extendY = GuideFocusExtendY.toPx(),
                amount = amount,
            ),
        )
    val radius = CornerRadius(GuideCardRadius.toPx())
    if (amount > 0f) {
        val dy = ShadowOffset.toPx() * amount
        val blur = ShadowBlur.toPx()
        drawIntoCanvas { canvas ->
            val paint =
                android.graphics.Paint().apply {
                    color = colors.focusShadow.copy(alpha = colors.shadowAlpha * amount).toArgb()
                    isAntiAlias = true
                    maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
                }
            canvas.nativeCanvas.drawRoundRect(
                bounds.left,
                bounds.top + dy,
                bounds.right,
                bounds.bottom + dy,
                radius.x,
                radius.y,
                paint,
            )
        }
    }
    drawRoundRect(
        color = lerp(resting, colors.focusFill, amount),
        topLeft = Offset(bounds.left, bounds.top),
        size = Size(bounds.width, bounds.height),
        cornerRadius = radius,
    )
    if (amount == 0f) drawRestingHighlight(colors, bounds, radius.x)
}

/** The lit top edge of a resting card: a 1 dp line inset by the corner radius so it stays inside the rounded ends. */
private fun DrawScope.drawRestingHighlight(
    colors: GuideColors,
    bounds: Rect,
    radius: Float,
) {
    val left = bounds.left + radius
    val right = bounds.right - radius
    if (right <= left) return
    val width = RestingHighlightWidth.toPx()
    drawLine(
        color = colors.plateHighlight.copy(alpha = RESTING_HIGHLIGHT_ALPHA),
        start = Offset(left, bounds.top + width / 2),
        end = Offset(right, bounds.top + width / 2),
        strokeWidth = width,
    )
}

/** A fully rounded pill. Transparent when [fill] is null: the page shows through. */
internal fun DrawScope.drawGuidePill(fill: Color?) {
    if (fill == null) return
    val radius = CornerRadius(size.minDimension / 2f)
    drawRoundRect(fill, cornerRadius = radius)
}
