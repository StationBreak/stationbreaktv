// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.stan

import kotlin.math.abs
import kotlin.math.min

/**
 * How the study's 1920 x 1080 stage sits in a canvas of [width] x [height] pixels: scaled uniformly to fit and centred,
 * so a canvas of another aspect gets bars either side (or above and below). [StanStage] draws with it, and a screen
 * uses the same numbers to put its own text where the study puts it, in stage coordinates.
 *
 * Stage numbers are in stage units; results are pixels in the canvas. Lengths (a font size, a gap) scale by [scale];
 * points also move by the letterbox offset.
 */
class StanStageTransform(
    val width: Float,
    val height: Float,
) {
    /** Pixels per stage unit; 0 when the canvas has no size. */
    val scale: Float =
        min(width / STAGE_W, height / STAGE_H).let { if (it.isFinite() && it > 0f) it else 0f }

    /** Where the stage's left edge is in the canvas: the letterbox bar's width. */
    val offsetX: Float = (width - STAGE_W * scale) / 2f

    /** Where the stage's top edge is in the canvas. */
    val offsetY: Float = (height - STAGE_H * scale) / 2f

    /** True when the canvas has the stage's aspect, so there are no bars. */
    val fillsCanvas: Boolean = abs(offsetX) < ONE_PIXEL && abs(offsetY) < ONE_PIXEL

    /** The canvas x of the stage x [stageX]. */
    fun x(stageX: Double): Float = offsetX + scale * stageX.toFloat()

    /** The canvas y of the stage y [stageY]. */
    fun y(stageY: Double): Float = offsetY + scale * stageY.toFloat()

    /** A stage length (a size, a gap) in canvas pixels. */
    fun length(stageLength: Double): Float = scale * stageLength.toFloat()

    /** The stage x of the canvas x [pixelX]; 0 when the canvas has no size. */
    fun stageX(pixelX: Float): Double = if (scale == 0f) 0.0 else ((pixelX - offsetX) / scale).toDouble()

    /** The stage y of the canvas y [pixelY]; 0 when the canvas has no size. */
    fun stageY(pixelY: Float): Double = if (scale == 0f) 0.0 else ((pixelY - offsetY) / scale).toDouble()

    private companion object {
        const val ONE_PIXEL = 0.5f
    }
}
