// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.stan

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import kotlinx.coroutines.isActive
import tv.stationbreak.core.ui.LocalMotionReduced

/**
 * Stan, animated: one of the five scenes of the "Stan In Motion" motion study, drawn on a Compose [Canvas] in the
 * study's 1920 x 1080 design space, scaled uniformly to fit [modifier]'s size and letterboxed (the ground fills the
 * bars) if the aspect differs. The screen puts it where it wants Stan and lays out the real text itself: the engine
 * draws no copy, no "Station Break" title and no clock. Read what the screen needs for that from
 * `scene.frame(t)`: [StanFrame.blocks] and [StanFrame.title].
 *
 * Time: with [timeSeconds] null, the stage drives its own clock from the frame clock (`withFrameNanos`) for as long as
 * it is composed, starting at 0 whenever the scene changes. A caller can instead pass the time to draw. When the TV's
 * "Remove animations" is on ([LocalMotionReduced]) nothing animates: one still frame at t = 3 is drawn, as the study
 * does, whatever [timeSeconds] says.
 *
 * [palette]: [StanPalette.Dark] (the default) is the warm ink ground with cream Stan; [StanPalette.Paper] is the light
 * mode, ink and cream traded, on the paper ground.
 *
 * Cost, for a 1.5 GB Fire TV: the animation clock changes only draw state, so a running stage redraws without
 * recomposing. A frame builds about 60 small shape objects (the scene function) and draws them straight to the canvas.
 * Nothing else is allocated per frame that matters: paths are parsed once and cached (the few that change every frame
 * reuse the slot of one pushed out), strokes, the crescent and the screen's clip are cached, glyphs are measured once,
 * and the static is six 48 x 45 bitmaps built once per process. The ground is one gradient and one tiled-dot fill,
 * the static's rolling bar and any fade are the only layers. The stage is decorative: give it semantics from outside
 * if a screen wants Stan described.
 */
@Composable
fun StanStage(
    scene: StanScene,
    modifier: Modifier = Modifier,
    timeSeconds: Float? = null,
    palette: StanPalette = StanPalette.Dark,
) {
    val reduced = LocalMotionReduced.current
    val clock = remember { mutableLongStateOf(0L) }
    val animate = timeSeconds == null && !reduced
    LaunchedEffect(scene, animate) {
        clock.longValue = 0L
        if (animate) {
            val start = withFrameNanos { it }
            while (isActive) withFrameNanos { clock.longValue = it - start }
        }
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val painter =
        remember(palette, density, measurer) {
            StanPainter(palette, StanGround(palette), StanGlyphs(measurer, density))
        }
    Canvas(modifier) {
        // The clock is read here, in the draw phase, so each tick redraws without recomposing.
        val t = stanTimeSeconds(timeSeconds, reduced, clock.longValue)
        painter.draw(this, scene.frame(t))
    }
}
