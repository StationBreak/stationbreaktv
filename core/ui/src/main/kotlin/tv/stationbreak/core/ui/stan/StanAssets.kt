// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // The ground's numbers come straight from the motion study's <defs>.

package tv.stationbreak.core.ui.stan

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import tv.stationbreak.core.ui.LIFT_RADIUS
import tv.stationbreak.core.ui.liftBrush

/** The stage the motion study draws on, in its own units. */
internal const val STAGE_W = 1920f
internal const val STAGE_H = 1080f

/**
 * The noise tiles for the static, built once per process from [StanNoise] (seeded, so always the same) and kept: six
 * small bitmaps of 48 x 45, about 8 KB each. Nothing is generated while a frame is drawn.
 */
internal object StanNoiseTiles {
    val images: List<ImageBitmap> by lazy {
        List(StanNoise.TILE_COUNT) { i ->
            Bitmap
                .createBitmap(StanNoise.tile(i), StanNoise.TILE_WIDTH, StanNoise.TILE_HEIGHT, Bitmap.Config.ARGB_8888)
                .asImageBitmap()
        }
    }
}

/**
 * The ground of one palette, built once (by the stage, per palette): the two radial lifts and the halftone dots.
 *
 * The study's gradients are SVG objectBoundingBox ones, so on a 16:9 stage they are ellipses (0.8 of the width across,
 * 0.8 of the height down). Compose's are circles; the ground is drawn into a square and squashed to the stage's height,
 * which gives the same ellipse.
 */
internal class StanGround(
    palette: StanPalette,
) {
    val left: Brush = lift(palette, .32f, .5f)
    val centre: Brush = lift(palette, .5f, .42f)

    /** 12 x 12 dots of radius 1.6 at 4.5%, laid on a lattice turned 30 degrees (the study's pattern). */
    val halftone: Brush = dots(palette)

    fun lift(bg: StanBackground): Brush =
        when (bg) {
            StanBackground.Left -> left
            StanBackground.Centre -> centre
            StanBackground.None -> error("StanBackground.None has no ground; drawGround draws nothing for it")
        }

    private fun lift(
        palette: StanPalette,
        cx: Float,
        cy: Float,
    ): Brush = liftBrush(palette, center = Offset(cx * STAGE_W, cy * STAGE_W), radius = LIFT_RADIUS * STAGE_W)

    private fun dots(palette: StanPalette): Brush {
        val tile = ImageBitmap(DOT_TILE, DOT_TILE)
        val paint =
            Paint().apply {
                isAntiAlias = true
                color = palette.dots
                alpha = .045f
            }
        Canvas(tile).drawCircle(Offset(DOT_TILE / 2f, DOT_TILE / 2f), 1.6f, paint)
        return ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }

    private companion object {
        const val DOT_TILE = 12
    }
}
