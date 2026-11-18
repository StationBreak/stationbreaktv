// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // The art's numbers are the motion study's own, in its 1920 x 1080 stage units.

package tv.stationbreak.feature.live.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import tv.stationbreak.core.ui.stan.StanFrame
import tv.stationbreak.core.ui.stan.StanPalette
import tv.stationbreak.core.ui.stan.StanRenderer
import tv.stationbreak.core.ui.stan.StanScene
import tv.stationbreak.core.ui.stan.StanStageTransform
import tv.stationbreak.core.ui.stan.rememberStanFrame
import tv.stationbreak.core.ui.stan.rememberStanRenderer

/*
 * How live TV runs Stan's engine (core:ui, `stan`), apart from the screen's layout (StanScreen.kt, built on the
 * engine's StanStageLayout): the one renderer kept for the whole live screen, the clock's rate for each scene, and the
 * art the study draws beside Stan (the wave rule and the standby blocks).
 */

/** Live TV is always Dark. */
internal val StanInk = StanPalette.Dark

/** The renderer the live screen keeps above its states, so a tune into Stan does not parse and measure again. */
internal val LocalStanRenderer = compositionLocalOf<StanRenderer?> { null }

/** Holds one [StanRenderer] for [content]'s life. It makes its painter only when Stan is first drawn. */
@Composable
internal fun ProvideStanRenderer(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStanRenderer provides rememberStanRenderer(StanInk), content = content)
}

// The slow scenes (a doze, a wobble) look the same at 30 frames a second and cost half; standby, where the picture
// comes and goes, keeps the display's rate.
private const val SLOW_SCENE_FPS = 30

/** The clock of [scene] at the rate live TV wants for it. */
@Composable
internal fun rememberSceneFrame(scene: StanScene): State<StanFrame> =
    rememberStanFrame(scene, maxFps = if (scene == StanScene.Standby) null else SLOW_SCENE_FPS)

private const val COLUMN_ART_X = 1004f
private const val WAVES = 6
private const val WAVE_REACH = 36f
private const val WAVE_BEND = 20f
private const val WAVE_CONTROL = 18f
private const val RULE_WIDTH = 9f
private const val BLOCK_COUNT = 7
private const val BLOCK_STEP = 50f
private const val BLOCK_SIZE = 30f
private const val BLOCK_STROKE = 3f

/**
 * Draws the wave rule at the stage y [ruleY] and, when [blocksTop] is given, the standby counter with its top at that
 * stage y and as many blocks lit as [lit] says. The path and strokes are made once per size; [lit] is read only while
 * drawing, so the layer redraws when the count changes and not on a tick.
 */
internal fun Modifier.stanArt(
    transform: StanStageTransform,
    ruleY: Float,
    blocksTop: Float?,
    lit: State<Int?>,
): Modifier =
    drawWithCache {
        val rule =
            Path().apply {
                moveTo(COLUMN_ART_X, ruleY)
                // A q 18 -20 36 0, then its reflection each time: the bend alternates.
                repeat(WAVES) { i ->
                    relativeQuadraticBezierTo(WAVE_CONTROL, if (i % 2 == 0) -WAVE_BEND else WAVE_BEND, WAVE_REACH, 0f)
                }
            }
        val ruleStroke = Stroke(RULE_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val outline = Stroke(BLOCK_STROKE)
        onDrawBehind {
            translate(transform.offsetX, transform.offsetY) {
                scale(transform.scale, transform.scale, pivot = Offset.Zero) {
                    drawPath(rule, StanInk.accent, style = ruleStroke)
                    val count = lit.value
                    if (blocksTop != null && count != null) drawBlocks(blocksTop, count, outline)
                }
            }
        }
    }

// Seven blocks, [lit] of them filled, outlined in the detail's grey.
private fun DrawScope.drawBlocks(
    top: Float,
    lit: Int,
    outline: Stroke,
) {
    val size = Size(BLOCK_SIZE, BLOCK_SIZE)
    for (i in 0 until BLOCK_COUNT) {
        val at = Offset(COLUMN_ART_X + i * BLOCK_STEP, top)
        if (i < lit) drawRect(StanInk.accent, at, size, style = Fill)
        drawRect(StanInk.grey, at, size, style = outline)
    }
}
