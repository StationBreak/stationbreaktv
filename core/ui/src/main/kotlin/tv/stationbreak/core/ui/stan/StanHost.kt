// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.stan

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.stationbreak.core.ui.BroadcastType
import tv.stationbreak.core.ui.LocalMotionReduced

/**
 * What Stan is up to as a host ([StanHost]). Each is a loop that starts again when the mood changes.
 *
 * Talking is not a mood: a new line makes him talk for a moment in whatever mood he is in.
 */
enum class StanMood {
    /** Standing, blinking, a gentle bob. */
    Idle,

    /** Working the aerial, as on the standby screen: something is under way. */
    Busy,

    /** Happy eyes, a smile and a little hop as it begins: it worked. */
    Pleased,

    /** The no-signal shrug: it did not work. */
    Shrug,

    /** Both arms up, bouncing: the big moments. */
    Cheer,

    /** Asleep, sitting, with Zs drifting up: nothing has happened for a while. No bubble, whatever the line. */
    Dozing,
}

/** Test tags for the host. The bubble's is for tests only: the host is hidden from accessibility. */
internal object StanHostTags {
    const val BUBBLE = "stanHost.bubble"
}

private val FIGURE_W = 220.dp
private val FIGURE_H = 236.dp
private val BUBBLE_MAX_W = 320.dp
private val BUBBLE_CORNER = 12.dp
private val TAIL_W = 20.dp
private val TAIL_H = 10.dp
private const val HOST_FPS = 20
private const val TALK_MILLIS = 1_200L
private const val POP_MILLIS = 160
private const val POP_FROM = .9f
private const val FADE_MILLIS = 200

/** The bubble pops from the tip of its tail, toward Stan: the bottom centre. */
private val TAIL_TIP = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 1f)

/**
 * Stan as a host in the corner of a screen: [mood], with his [line] in a speech bubble above him. There is no bubble
 * for a null or blank line, nor while he dozes.
 *
 * He is decoration and never the message: the host has no semantics at all (a screen reader skips him and his line)
 * and holds nothing focusable, so the screen's own text must say everything the viewer needs. [line] is "Stan's
 * line" from DESIGN.md: Ranchers, capitals, a few words at most.
 *
 * A new line pops the bubble in and has him talk for a moment; a new mood cross-fades him. With "Remove animations"
 * on ([LocalMotionReduced]) each mood is a still frame, the bubble just appears, a mood change cuts, and he does not
 * talk. Stan is drawn at 220 x 236 dp, at no more than 20 frames a second, on [palette]'s colours with no ground of
 * his own. [renderer] must be for [palette]; hoist one with [rememberStanRenderer] when the host comes and goes.
 *
 * Placement: the host is 320 dp wide (the widest bubble) and as tall as Stan plus the bubble. Lay it as an overlay in
 * a `Box`, aligned `BottomEnd` (or `BottomStart`) inside the safe area, so Stan stays put as lines come and go.
 */
@Composable
fun StanHost(
    mood: StanMood,
    line: String?,
    modifier: Modifier = Modifier,
    palette: StanPalette = StanPalette.Dark,
    renderer: StanRenderer = rememberStanRenderer(palette),
) {
    val reduced = LocalMotionReduced.current
    val shown = hostLine(mood, line)
    val talking = remember { mutableStateOf(false) }
    LaunchedEffect(shown, reduced) {
        talking.value = shown != null && !reduced
        if (talking.value) {
            delay(TALK_MILLIS)
            talking.value = false
        }
    }
    Column(
        // As wide as the widest bubble, so Stan stays put whatever the line's length.
        Modifier
            .width(BUBBLE_MAX_W)
            .then(modifier)
            .clearAndSetSemantics {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StanBubble(shown, palette)
        Crossfade(
            targetState = mood,
            animationSpec = if (reduced) snap() else tween(FADE_MILLIS),
            label = "stanHostMood",
        ) { shown ->
            StanFigure(shown, talking, renderer, Modifier.size(FIGURE_W, FIGURE_H))
        }
    }
}

/** Stan in [mood] on his own clock, read only while drawing, so each tick redraws without recomposing. */
@Composable
private fun StanFigure(
    mood: StanMood,
    talking: State<Boolean>,
    renderer: StanRenderer,
    modifier: Modifier,
) {
    val reduced = LocalMotionReduced.current
    val clock = rememberStanClock(mood, null, HOST_FPS)
    val frame =
        remember(mood, reduced) {
            derivedStateOf {
                hostFrame(mood, stanTimeSeconds(null, reduced, clock.longValue), talking.value && !reduced)
            }
        }
    val painter = renderer.painter
    Spacer(
        modifier.drawWithCache {
            val transform = hostTransform(size.width, size.height)
            onDrawBehind { painter.drawPicture(this, transform, frame.value) }
        },
    )
}

/**
 * Stan's speech bubble: [line] in ink Ranchers on a cream rounded box, with a tail pointing down at him; nothing when
 * [line] is null. A new line replaces the old at once and pops in (from 90%, over 160 ms, unless motion is reduced).
 * The colours are [palette]'s, so on paper the bubble is ink with cream lettering.
 */
@Composable
internal fun StanBubble(
    line: String?,
    palette: StanPalette,
    modifier: Modifier = Modifier,
) {
    if (line == null) return
    val reduced = LocalMotionReduced.current
    val pop = remember(line) { Animatable(if (reduced) 1f else POP_FROM) }
    LaunchedEffect(pop) { pop.animateTo(1f, tween(POP_MILLIS)) }
    Column(
        modifier
            .widthIn(max = BUBBLE_MAX_W)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
                transformOrigin = TAIL_TIP
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            line,
            Modifier
                .testTag(StanHostTags.BUBBLE)
                .background(palette.cream, RoundedCornerShape(BUBBLE_CORNER))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            color = palette.ink,
            style = TextStyle(fontFamily = BroadcastType.Voice, fontSize = 22.sp, textAlign = TextAlign.Center),
        )
        // Tucked a pixel up under the box, so no seam shows between the two.
        Spacer(
            Modifier.offset(y = (-1).dp).size(TAIL_W, TAIL_H).drawWithCache {
                val tail =
                    Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width / 2f, size.height)
                        close()
                    }
                onDrawBehind { drawPath(tail, palette.cream) }
            },
        )
    }
}
