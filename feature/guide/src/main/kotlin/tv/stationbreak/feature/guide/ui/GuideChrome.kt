// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** The focused card does not grow: focus is a fill change only. [guideFocusBounds] still takes an extent. */
internal val GuideFocusExtendX = 0.dp
internal val GuideFocusExtendY = 0.dp

/** Programme radius: nothing inside the grid is rounder than 2 dp. */
internal val GuideCardRadius = 2.dp

/** Extra inset inside the cell's own 1 dp padding. None: the cells meet at the 1 dp the cell padding leaves. */
internal val GuideCardInset = 0.dp

/** A focused programme does not scale. */
internal const val CONTENT_SCALE = 1f

private const val FOCUS_MOTION_MS = 160

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
 * The programme card. [amount] is 0 at rest and 1 when focused: the fill moves from [resting] to the focus fill. There
 * is no growth, shadow, highlight line, ring or second stroke.
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
    drawRoundRect(
        color = lerp(resting, colors.focusFill, amount),
        topLeft = Offset(bounds.left, bounds.top),
        size = Size(bounds.width, bounds.height),
        cornerRadius = CornerRadius(GuideCardRadius.toPx()),
    )
}

/** A fully rounded pill. Transparent when [fill] is null: the page shows through. */
internal fun DrawScope.drawGuidePill(fill: Color?) {
    if (fill == null) return
    val radius = CornerRadius(size.minDimension / 2f)
    drawRoundRect(fill, cornerRadius = radius)
}
