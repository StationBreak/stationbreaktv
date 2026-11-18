// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MatchingDeclarationName") // The adapter file: its geometry class and its backdrop composable.

package tv.stationbreak.feature.live.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.stationbreak.core.ui.stan.StanPalette
import tv.stationbreak.core.ui.stan.StanScene
import tv.stationbreak.core.ui.stan.StanStage
import tv.stationbreak.core.ui.stan.StanStageTransform
import tv.stationbreak.core.ui.stan.rememberStanFrame

/*
 * The one place live TV touches the animated-Stan engine (core:ui, `stan`): a scene drawn on the engine's one clock
 * (`rememberStanFrame`), the standby block count read from that same frame, and the stage's geometry. Everything else
 * in feature:live (the specs, the screen's text) knows only this file, so an engine API change is a change here.
 */

/** Where the study's 1920 x 1080 stage sits in a box of [width] x [height] dp: scaled to fit, centred. */
internal class StageGeometry(
    width: Dp,
    height: Dp,
) {
    // Worked in dp: the transform is unit-agnostic, so a box in dp gives dp.
    private val transform = StanStageTransform(width.value, height.value)

    /** How many dp one stage unit is. */
    val unit: Dp = transform.scale.dp

    fun x(units: Float): Dp = transform.x(units.toDouble()).dp

    fun y(units: Float): Dp = transform.y(units.toDouble()).dp
}

/**
 * [scene] drawn to fill the box, with [overlay] drawn over it in stage units (the origin is the stage's top-left, and
 * the stage is scaled to fit, as the stage itself is). [overlay] is given how many of the standby blocks are lit in the
 * very frame the stage is drawing, null for a scene with none. Both read the frame in the draw phase, so a tick redraws
 * without recomposing.
 */
@Composable
internal fun StanBackdrop(
    scene: StanScene,
    modifier: Modifier = Modifier,
    overlay: DrawScope.(blocks: Int?) -> Unit,
) {
    // Live TV is always Dark.
    val frame = rememberStanFrame(scene)
    Box(modifier.fillMaxSize().testTag(LiveTags.STAN_STAGE)) {
        StanStage(frame, Modifier.fillMaxSize(), StanPalette.Dark)
        Canvas(Modifier.fillMaxSize()) {
            val stage = StanStageTransform(size.width, size.height)
            translate(stage.offsetX, stage.offsetY) {
                scale(stage.scale, stage.scale, pivot = Offset.Zero) { overlay(frame.value.blocks) }
            }
        }
    }
}
