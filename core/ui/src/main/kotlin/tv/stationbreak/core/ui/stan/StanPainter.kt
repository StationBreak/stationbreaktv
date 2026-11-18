// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // Stage and screen geometry, in the motion study's own units.

package tv.stationbreak.core.ui.stan

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Draws [StanFrame]s: the ground, the stage-space shapes, the shadow and Stan inside his group transform, every shape
 * kind, and the clip/push/pop markers, in the study's 1920 x 1080 space scaled uniformly to fit (letterboxed).
 *
 * Built once per palette and kept by the stage. It owns the caches that keep steady animation free of per-frame work:
 * parsed paths ([StanPathCache]), stroke styles by width, the crescent's cut-out path, the screen's clip path and one
 * layer [Paint]. Drawing itself creates only value types (offsets, sizes, colours).
 */
internal class StanPainter(
    private val palette: StanPalette,
    private val ground: StanGround,
    private val glyphs: StanGlyphs,
) {
    private val paths = StanPathCache()
    private val strokeWidths = FloatArray(STROKE_SLOTS)
    private val strokeStyles = arrayOfNulls<Stroke>(STROKE_SLOTS)
    private var strokeCount = 0
    private val crescents = HashMap<StanCrescent, Path>()
    private val layerPaint = Paint()
    private val screenClip = Path().apply { addRoundRect(SCREEN) }

    /**
     * Draws the ground: the lift and the halftone dots of the stage, and, if the canvas is not the stage's aspect,
     * the ground's edge colour in the bars either side. Nothing here depends on the frame but [bg], so the stage
     * caches it (see StanStage). [StanBackground.None] draws nothing, not even the bars.
     */
    fun drawGround(
        scope: DrawScope,
        tf: StanStageTransform,
        bg: StanBackground,
    ) = with(scope) {
        if (tf.scale > 0f && bg != StanBackground.None) {
            if (!tf.fillsCanvas) drawRect(palette.groundEdge)
            withTransform({
                translate(tf.offsetX, tf.offsetY)
                scale(tf.scale, tf.scale, Offset.Zero)
            }) {
                withTransform({ scale(1f, STAGE_H / STAGE_W, Offset.Zero) }) {
                    drawRect(ground.lift(bg), size = Size(STAGE_W, STAGE_W))
                }
                // Rotated 30 degrees, so the rectangle is the stage's corners turned back by 30 degrees.
                withTransform({ rotate(30f, Offset.Zero) }) {
                    drawRect(ground.halftone, topLeft = Offset(0f, -960f), size = Size(2204f, 1896f))
                }
            }
        }
    }

    /** Draws [frame]'s picture over the ground: what is behind Stan, his shadow and Stan, faded by [StanFrame.fade]. */
    fun drawPicture(
        scope: DrawScope,
        tf: StanStageTransform,
        frame: StanFrame,
    ) = with(scope) {
        if (tf.scale > 0f) {
            withTransform({
                translate(tf.offsetX, tf.offsetY)
                scale(tf.scale, tf.scale, Offset.Zero)
            }) {
                faded(frame.fade, tf.stageBounds) { drawStage(frame) }
            }
        }
    }

    /** Draws [block], at [fade] (0..1) as one piece: Stan and his shadow fade together, not each on its own. */
    private inline fun DrawScope.faded(
        fade: Double,
        bounds: Rect,
        block: DrawScope.() -> Unit,
    ) {
        if (fade >= 1.0) {
            block()
        } else if (fade > 0.0) {
            layerPaint.alpha = fade.toFloat()
            drawContext.canvas.saveLayer(bounds, layerPaint)
            block()
            drawContext.canvas.restore()
        }
    }

    private fun DrawScope.drawStage(frame: StanFrame) {
        for (i in frame.back.indices) drawShape(frame.back[i])
        val sh = frame.shadow
        drawOval(
            palette.shadow,
            topLeft = Offset((sh.cx - sh.rx).toFloat(), (sh.cy - sh.ry).toFloat()),
            size = Size((sh.rx * 2).toFloat(), (sh.ry * 2).toFloat()),
            alpha = palette.shadowAlpha,
        )
        withTransform({
            translate(frame.x.toFloat(), frame.y.toFloat())
            rotate(frame.rot.toFloat(), Offset.Zero)
            scale(frame.sc.toFloat(), (frame.sc * frame.sy).toFloat(), Offset.Zero)
        }) {
            for (i in frame.stan.indices) drawShape(frame.stan[i])
        }
    }

    private fun DrawScope.drawShape(shape: StanShape) {
        when (shape) {
            is StanStroke -> {
                drawPath(paths.get(shape.d), palette.color(shape.c), shape.a.toFloat(), strokeFor(shape.w))
            }

            is StanFill -> {
                drawPath(paths.get(shape.d), palette.color(shape.c), shape.a.toFloat(), Fill)
            }

            is StanCircle -> {
                drawCircleShape(shape)
            }

            is StanOval -> {
                drawOval(
                    palette.color(shape.c),
                    topLeft = Offset((shape.x - shape.rx).toFloat(), (shape.y - shape.ry).toFloat()),
                    size = Size((shape.rx * 2).toFloat(), (shape.ry * 2).toFloat()),
                    alpha = shape.a.toFloat(),
                )
            }

            is StanRoundRect -> {
                drawRoundRectShape(shape)
            }

            is StanGlyph -> {
                drawGlyph(shape)
            }

            is StanSnow -> {
                drawSnow(shape)
            }

            is StanCrescent -> {
                drawPath(crescent(shape), palette.color(shape.c))
            }

            is StanPush -> {
                drawContext.canvas.save()
                drawContext.canvas.rotate(shape.rot.toFloat(), shape.px.toFloat(), shape.py.toFloat())
            }

            is StanClip -> {
                drawContext.canvas.save()
                drawContext.canvas.clipPath(paths.get(shape.d))
            }

            StanPop -> {
                drawContext.canvas.restore()
            }
        }
    }

    private fun DrawScope.drawCircleShape(c: StanCircle) {
        val style = if (c.sw > 0) strokeFor(c.sw) else Fill
        drawCircle(palette.color(c.c), c.r.toFloat(), Offset(c.x.toFloat(), c.y.toFloat()), c.a.toFloat(), style)
    }

    private fun DrawScope.drawRoundRectShape(r: StanRoundRect) {
        val turned = r.rot != 0.0
        if (turned) {
            drawContext.canvas.save()
            drawContext.canvas.rotate(r.rot.toFloat(), r.px.toFloat(), r.py.toFloat())
        }
        val topLeft = Offset(r.x.toFloat(), r.y.toFloat())
        val size = Size(r.w.toFloat(), r.h.toFloat())
        val corner = CornerRadius(r.r.toFloat())
        drawRoundRect(palette.color(r.c), topLeft, size, corner)
        if (r.sc != null) drawRoundRect(palette.color(r.sc), topLeft, size, corner, style = strokeFor(r.sw))
        if (turned) drawContext.canvas.restore()
    }

    /**
     * A glyph is measured once at a fixed size and drawn scaled, so a growing Z or a pulsing "?" never has to be laid
     * out again. The study's y is the baseline; [StanGlyph.mid] centres the character on x.
     */
    private fun DrawScope.drawGlyph(g: StanGlyph) {
        val layout = glyphs.layout(g.ch)
        val k = (g.size / StanGlyphs.REFERENCE_PX).toFloat()
        val dx = if (g.mid) -layout.size.width / 2f else 0f
        withTransform({
            translate(g.x.toFloat(), g.y.toFloat())
            scale(k, k, Offset.Zero)
        }) {
            drawText(
                layout,
                color = palette.color(g.c),
                topLeft = Offset(dx, -layout.firstBaseline),
                alpha = g.a.toFloat(),
            )
        }
    }

    /**
     * Static on the screen: the tile, unsmoothed so it stays grainy, with a pale bar rolling down it, clipped to the
     * screen. At less than full alpha the two go into a layer together, as the study's group opacity does.
     */
    private fun DrawScope.drawSnow(snow: StanSnow) {
        val canvas = drawContext.canvas
        canvas.save()
        canvas.clipPath(screenClip)
        val layered = snow.a < 1.0
        if (layered) {
            layerPaint.alpha = snow.a.toFloat()
            canvas.saveLayer(SCREEN_BOUNDS, layerPaint)
        }
        drawImage(
            StanNoiseTiles.images[snow.tile.coerceIn(0, StanNoise.TILE_COUNT - 1)],
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(StanNoise.TILE_WIDTH, StanNoise.TILE_HEIGHT),
            dstOffset = IntOffset(-100, -70),
            dstSize = IntSize(144, 136),
            filterQuality = FilterQuality.None,
        )
        drawRect(
            palette.cream,
            topLeft = Offset(-100f, (-70 + snow.roll * 136).toFloat()),
            size = Size(144f, 22f),
            alpha = .22f,
        )
        if (layered) canvas.restore()
        canvas.restore()
    }

    private fun crescent(c: StanCrescent): Path =
        crescents.getOrPut(c) {
            val moon = Path().apply { addOval(Rect(Offset(c.x.toFloat(), c.y.toFloat()), c.r.toFloat())) }
            val bite = Path().apply { addOval(Rect(Offset(c.ox.toFloat(), c.oy.toFloat()), c.or.toFloat())) }
            Path.combine(PathOperation.Difference, moon, bite)
        }

    /** The round-capped stroke of [width]: the few widths in use (3 to 11) are kept in two small arrays. */
    private fun strokeFor(width: Double): Stroke {
        val w = width.toFloat()
        for (i in 0 until strokeCount) {
            val known = strokeStyles[i]
            if (known != null && strokeWidths[i] == w) return known
        }
        val style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (strokeCount < STROKE_SLOTS) {
            strokeWidths[strokeCount] = w
            strokeStyles[strokeCount] = style
            strokeCount++
        }
        return style
    }

    private companion object {
        const val STROKE_SLOTS = 16
        val SCREEN_BOUNDS = Rect(-100f, -70f, 44f, 66f)
        val SCREEN = RoundRect(-100f, -70f, 44f, 66f, CornerRadius(30f))
    }
}
