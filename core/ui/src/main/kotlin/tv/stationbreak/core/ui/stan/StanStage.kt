// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.stan

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.isActive
import tv.stationbreak.core.ui.LocalMotionReduced
import kotlin.math.roundToInt

/**
 * The clock of a Stan scene: the current [StanFrame] of [scene], one new frame per display frame for as long as this is
 * composed. This is the one place the clock lives; hand the result to [StanStage] or [StanStageLayout] and read
 * [StanFrame.blocks], [StanFrame.title] and [StanFrame.fade] from it for your own text.
 *
 * Read the returned state in the draw phase (a `drawWithContent`, a `graphicsLayer {}` block or another lambda
 * modifier) so a tick redraws without recomposing. Reading `.value` in composition recomposes the reader on every tick.
 *
 * - **Time:** with [timeSeconds] null, the frame clock (`withInfiniteAnimationFrameNanos`) drives it, from 0.
 *   The clock restarts, with no frame of the old scene in between, whenever [scene] changes. With [timeSeconds]
 *   set, that time is used instead.
 * - **Reduced motion:** when the TV's "Remove animations" is on ([LocalMotionReduced]), nothing runs: the frame at
 *   t = 3 s (the study does the same), whatever [timeSeconds] says.
 * - **Cost:** the frame is built once per tick, however many places read it.
 */
@Composable
fun rememberStanFrame(
    scene: StanScene,
    timeSeconds: Double? = null,
): State<StanFrame> {
    val reduced = LocalMotionReduced.current
    val clock = remember(scene) { mutableLongStateOf(0L) }
    val explicit = rememberUpdatedState(timeSeconds)
    val animate = timeSeconds == null && !reduced
    LaunchedEffect(clock, animate) {
        if (animate) {
            clock.longValue = 0L
            // The infinite-animation clock, as Snow uses: a Compose test can still go idle while Stan moves.
            val start = withInfiniteAnimationFrameNanos { it }
            while (isActive) withInfiniteAnimationFrameNanos { clock.longValue = it - start }
        }
    }
    return remember(scene, reduced) {
        derivedStateOf { scene.frame(stanTimeSeconds(explicit.value, reduced, clock.longValue)) }
    }
}

/**
 * Stan, animated, with no text to keep in step: one of the five scenes of the "Stan In Motion" motion study, with its
 * own clock ([rememberStanFrame]). A screen that lays out copy beside him, or fades text with the opening, calls
 * [rememberStanFrame] itself and uses the [StanStage] that takes the frame.
 */
@Composable
fun StanStage(
    scene: StanScene,
    modifier: Modifier = Modifier,
    timeSeconds: Double? = null,
    palette: StanPalette = StanPalette.Dark,
) {
    StanStage(rememberStanFrame(scene, timeSeconds), modifier, palette)
}

/**
 * Draws [frame]: the ground, then Stan and his surroundings, in the study's 1920 x 1080 design space, scaled uniformly
 * to fit [modifier]'s size and letterboxed (the ground's edge colour fills the bars) if the aspect differs. The
 * engine draws no copy, no "Station Break" title and no clock: a screen lays out its own text (see [StanStageLayout]).
 *
 * [frame] is read only while drawing, so each tick redraws without recomposing. The stage is decorative: give it
 * semantics from outside if a screen wants Stan described.
 *
 * [palette]: [StanPalette.Dark] (the default) is the warm ink ground with cream Stan; [StanPalette.Paper] is the light
 * mode, ink and cream traded, on the paper ground.
 *
 * Cost, for a 1.5 GB Fire TV: a frame is about 60 small shape objects plus some path strings (the ones with a moving
 * number in them), built by the scene function, and drawn straight to the canvas. The path strings are parsed once and
 * cached (the few that change every frame reuse the slot of one pushed out), as are strokes, the crescent and the
 * screen's clip; glyphs are measured once, and the static is six 48 x 45 bitmaps built once per process. The ground is
 * one gradient and one tiled-dot fill; the static's rolling bar and a fade are the only layers.
 */
@Composable
fun StanStage(
    frame: State<StanFrame>,
    modifier: Modifier = Modifier,
    palette: StanPalette = StanPalette.Dark,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val painter =
        remember(palette, density, measurer) {
            StanPainter(palette, StanGround(palette), StanGlyphs(measurer, density))
        }
    // Only changes when the scene's ground does, so the cache below is rebuilt on a resize or a new ground, not a tick.
    val ground = remember(frame) { derivedStateOf { frame.value.bg } }
    Spacer(
        modifier.drawWithCache {
            val transform = StanStageTransform(size.width, size.height)
            val bg = ground.value
            onDrawBehind {
                painter.drawGround(this, transform, bg)
                painter.drawPicture(this, transform, frame.value)
            }
        },
    )
}

/**
 * Stan with room for the screen's own text. The stage is drawn as [StanStage] draws it, filling this layout's size
 * (give it a bounded size), and [content] is laid over it with [StanStageScope], which places children by stage
 * coordinates, so the text keeps its place against Stan at any size or aspect.
 *
 * **Where the study puts its copy** (stage units, 1920 x 1080; a point is a text baseline unless noted). A screen
 * puts the same lines in the same places and sizes. Brand faces: Ranchers (`BroadcastType.Voice`) for the headline
 * and name, Space Mono Bold (`BroadcastType.Mono`) for the rest. Colours: cream, accent and grey, as the palette
 * has them.
 *
 * - **Headline** (the slates): size 156, Ranchers, cream, left edge x = 1000. One line: baseline y = 545; two lines:
 *   470 and 620 (150 apart). Below the last line, `yb` being its baseline:
 * - **Wave rule**: accent, stroke 9, from x = 1004 at y = `yb` + 52 ("q18 -20 36 0" then "t36 0" five more times).
 * - **Status label**: size 32, mono, accent, letter spacing 4, left edge x = 1004, baseline `yb` + 124.
 * - **Sub line**: size 28, mono, grey, letter spacing 2, x = 1004, baseline `yb` + 168.
 * - **Blocks** (standby): seven squares 30 x 30 at x = 1004 + 50 i, top y = `yb` + 196, outline grey 3; the first
 *   [StanFrame.blocks] are filled accent.
 * - **Brand name** (bottom left): "Station Break", size 46, Ranchers, cream, x = 96, baseline 1000.
 * - **Clock** (bottom right): size 28, mono, grey, right edge x = 1824, baseline 1000.
 * - **Load screen title** (opening, from [StanFrame.title]): "Station " in cream then "Break" in accent, size 150,
 *   Ranchers, centred on x = 960, baseline 880 + [StanTitle.offsetY]; tagline under it, size 28, mono, grey, letter
 *   spacing 10, centred on x = 960, baseline 952. Alphas are the [StanTitle] fields; multiply by [StanFrame.fade].
 *
 * `atStage` positions a child's top-left corner, not a baseline: for a line of text, offset by the font's ascent (or
 * use `Modifier.alignByBaseline` in a row), or place the text by `Modifier.layout` with its first baseline.
 */
@Composable
fun StanStageLayout(
    frame: State<StanFrame>,
    modifier: Modifier = Modifier,
    palette: StanPalette = StanPalette.Dark,
    content: @Composable StanStageScope.() -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        StanStage(frame, Modifier.matchParentSize(), palette)
        if (constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
            val scope =
                remember(constraints.maxWidth, constraints.maxHeight, density) {
                    val transform = StanStageTransform(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                    StanStageScope(transform, density)
                }
            scope.content()
        }
    }
}

/** What [StanStageLayout]'s content can use: the [transform] in force, and the stage's units as Compose's. */
class StanStageScope internal constructor(
    /** The stage's place in this layout, in pixels. */
    val transform: StanStageTransform,
    private val density: Density,
) {
    /** Puts the top-left corner of this element at the stage point ([x], [y]). */
    fun Modifier.atStage(
        x: Double,
        y: Double,
    ): Modifier = offset { IntOffset(transform.x(x).roundToInt(), transform.y(y).roundToInt()) }

    /** A stage length as [Dp]. */
    fun stageDp(length: Double): Dp = with(density) { transform.length(length).toDp() }

    /** A stage length as [TextUnit]: a stage font size as a Compose one. */
    fun stageSp(length: Double): TextUnit = with(density) { transform.length(length).toSp() }
}
