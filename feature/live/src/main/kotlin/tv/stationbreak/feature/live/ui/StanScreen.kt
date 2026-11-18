// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // The copy's positions are the motion study's own, in its 1920 x 1080 stage units.

package tv.stationbreak.feature.live.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.stationbreak.core.ui.BroadcastColors
import tv.stationbreak.core.ui.BroadcastType
import tv.stationbreak.core.ui.LocalScreenFit
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.feature.live.R
import java.util.Date
import java.util.Locale
import kotlin.math.max

// Where the copy sits, from the motion study's `copy()`: a baseline or top in stage units, and a size.
private const val COLUMN_X = 1000f
private const val DETAIL_X = 1004f
private const val ONE_LINE_Y = 545f
private const val TWO_LINE_Y = 470f
private const val LINE_STEP = 150f
private const val HEADLINE_SIZE = 156f
private const val SQUIGGLE_DROP = 52f
private const val SQUIGGLE_WIDTH = 9f
private const val STATUS_DROP = 124f
private const val STATUS_SIZE = 32f
private const val STATUS_SPACING_EM = 0.125f
private const val DETAIL_DROP = 168f
private const val DETAIL_SIZE = 28f
private const val DETAIL_SPACING_EM = 0.07f
private const val BLOCKS_DROP = 196f
private const val BLOCK_COUNT = 7
private const val BLOCK_STEP = 50f
private const val BLOCK_SIZE = 30f
private const val BLOCK_STROKE = 3f
private const val SIGN_OFF_SIZE = 46f
private const val CLOCK_SIZE = 28f

// The sign-off's baseline is 26 stage units above the safe area's bottom edge (80 above the stage's, which is 5% in).
private const val SIGN_OFF_RISE = 26f

// A squiggle of six waves under the headline: q 18 -20 36 0, then the reflected control point each time.
private const val WAVES = 6
private const val WAVE_REACH = 36f
private const val WAVE_BEND = 20f
private const val WAVE_CONTROL = 18f

/** The project's text floor: nothing is set smaller, however small the stage is drawn (the guide's corner). */
private val TEXT_FLOOR = 10.sp
private const val MAX_FONT_SCALE = 1.2f
private const val CLOCK_TICK_MS = 10_000L

/**
 * One of Stan's station screens: the animated stage and, over it, the copy of [spec] laid out as the motion study does
 * (Ranchers line or lines, a squiggle, the teletype status, the grey detail, the standby block counter) and, when
 * [full] (the whole screen, not the guide's corner), the "Station Break" sign-off and the clock at the safe area's
 * bottom corners, where the cable banner sits over them. [nowMs] is the clock's time.
 *
 * A screen reader is told [StanScreenSpec.spoken] once, politely, when there is one: the plain status, not Stan's
 * line.
 */
@Composable
internal fun StanScreen(
    spec: StanScreenSpec,
    nowMs: () -> Long,
    modifier: Modifier = Modifier,
    full: Boolean = true,
) {
    val spoken = spec.spoken?.text()
    val live =
        if (spoken == null) {
            Modifier
        } else {
            Modifier.semantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            }
        }
    BoxWithConstraints(modifier.fillMaxSize().testTag(spec.tag).then(live)) {
        val screen = DpSize(maxWidth, maxHeight)
        val safe = ScreenLayout.safeArea(screen, LocalScreenFit.current)
        val stage = remember(screen) { StageGeometry(maxWidth, maxHeight) }
        val lines =
            spec.headline
                .text()
                .uppercase()
                .split('\n')
        val top = if (lines.size == 2) TWO_LINE_Y else ONE_LINE_Y
        val lastLine = top + (lines.size - 1) * LINE_STEP
        StanBackdrop(spec.scene) { blocks ->
            drawSquiggle(lastLine + SQUIGGLE_DROP, StanColors.accent)
            if (blocks != null) drawBlocks(lastLine + BLOCKS_DROP, blocks)
        }
        val right = if (full) safe.right else maxWidth
        val place = CopyPlace(stage, right - stage.x(COLUMN_X), StanText(LocalDensity.current.fontScale, stage.unit))
        lines.forEachIndexed { i, line -> Headline(line, place, top + i * LINE_STEP) }
        Copy(spec.status.text(), place, lastLine + STATUS_DROP, LiveTags.STAN_STATUS, StatusLook)
        spec.detail?.let { Copy(it.text(), place, lastLine + DETAIL_DROP, spec.detailTag, DetailLook) }
        if (full) SignOffAndClock(safe, place, nowMs)
    }
}

// Where the copy goes on a stage: its geometry, the width the column has, and the text sizes.
private class CopyPlace(
    val stage: StageGeometry,
    val width: Dp,
    val text: StanText,
)

// How a line of teletype looks: the study's size, tracking and colour.
private class Look(
    val units: Float,
    val spacingEm: Float,
    val color: Color,
)

private val StatusLook = Look(STATUS_SIZE, STATUS_SPACING_EM, BroadcastColors.Accent)
private val DetailLook = Look(DETAIL_SIZE, DETAIL_SPACING_EM, BroadcastColors.Grey)

@Composable
private fun Headline(
    line: String,
    place: CopyPlace,
    top: Float,
) {
    Text(
        text = line,
        modifier =
            Modifier
                .atBaseline(place.stage.x(COLUMN_X), place.stage.y(top), place.width)
                .testTag(LiveTags.STAN_HEADLINE),
        style = place.text.style(BroadcastType.Voice, HEADLINE_SIZE, FontWeight.Normal),
        color = BroadcastColors.Cream,
        maxLines = 1,
        softWrap = false,
    )
}

// A teletype line (status or detail) in capitals, at [baseline] stage units.
@Composable
private fun Copy(
    text: String,
    place: CopyPlace,
    baseline: Float,
    tag: String,
    look: Look,
) {
    Text(
        text = text.uppercase(),
        modifier =
            Modifier
                .atBaseline(place.stage.x(DETAIL_X), place.stage.y(baseline), place.width)
                .testTag(tag),
        style = place.text.style(BroadcastType.Mono, look.units, FontWeight.Bold, look.spacingEm),
        color = look.color,
        maxLines = 2,
    )
}

// The station's sign-off at the safe area's bottom left, the time of day at its bottom right.
@Composable
private fun SignOffAndClock(
    safe: DpRect,
    place: CopyPlace,
    nowMs: () -> Long,
) {
    val baseline = safe.bottom - place.stage.unit * SIGN_OFF_RISE
    val width = safe.right - safe.left
    Text(
        text = stringResource(R.string.live_stan_sign_off),
        modifier = Modifier.atBaseline(safe.left, baseline, width).testTag(LiveTags.STAN_SIGN_OFF),
        style = place.text.style(BroadcastType.Display, SIGN_OFF_SIZE, FontWeight.Normal),
        color = BroadcastColors.Cream,
        maxLines = 1,
    )
    Text(
        text = clockText(nowMs),
        modifier = Modifier.atBaseline(safe.right, baseline, width, alignEnd = true).testTag(LiveTags.STAN_CLOCK),
        style = place.text.style(BroadcastType.Mono, CLOCK_SIZE, FontWeight.Bold).copy(textAlign = TextAlign.End),
        color = BroadcastColors.Grey,
        maxLines = 1,
    )
}

// A handful of short strings, formatted once when they change: the copy of the array is of no cost.
@Suppress("SpreadOperator")
@Composable
private fun Phrase.text(): String = stringResource(id, *args.toTypedArray())

/** The time of day in the device's own format, kept up to date while the screen is up. */
@Composable
private fun clockText(nowMs: () -> Long): String {
    val context = LocalContext.current
    val format = remember(context) { DateFormat.getTimeFormat(context) }
    val read by rememberUpdatedState(nowMs)
    val text by produceState(format.format(Date(read())), format) {
        while (true) {
            value = format.format(Date(read()))
            delay(CLOCK_TICK_MS)
        }
    }
    return text
}

private object StanColors {
    val accent: Color = BroadcastColors.Accent
    val grey: Color = BroadcastColors.Grey
}

/** The study's text sizes on this stage: a stage unit is [unit] dp, kept to the project's floor and the font scale. */
private class StanText(
    private val fontScale: Float,
    private val unit: Dp,
) {
    fun style(
        family: androidx.compose.ui.text.font.FontFamily,
        units: Float,
        weight: FontWeight,
        spacingEm: Float = 0f,
    ): TextStyle {
        val scaled = (unit.value * units * fontScale.coerceIn(1f, MAX_FONT_SCALE) / fontScale).sp
        val size = if (scaled < TEXT_FLOOR) TEXT_FLOOR else scaled
        return TextStyle(
            fontFamily = family,
            fontWeight = weight,
            fontSize = size,
            lineHeight = size,
            letterSpacing = size * spacingEm,
        )
    }
}

/**
 * Places the text with its first baseline at [baseline] and its left edge at [x] (its right edge, with [alignEnd]),
 * no wider than [width]: the study gives baselines, not tops. The element takes the parent's whole size.
 */
private fun Modifier.atBaseline(
    x: Dp,
    baseline: Dp,
    width: Dp,
    alignEnd: Boolean = false,
): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(Constraints(maxWidth = max(0, width.roundToPx())))
        val left = if (alignEnd) x.roundToPx() - placeable.width else x.roundToPx()
        val top = baseline.roundToPx() - placeable[FirstBaseline]
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(left, top) }
    }

// The wavy line under the headline, in stage units.
private fun DrawScope.drawSquiggle(
    y: Float,
    color: Color,
) {
    val path = Path().apply { moveTo(DETAIL_X, y) }
    repeat(WAVES) { i ->
        val bend = if (i % 2 == 0) -WAVE_BEND else WAVE_BEND
        path.relativeQuadraticBezierTo(WAVE_CONTROL, bend, WAVE_REACH, 0f)
    }
    drawPath(path, color, style = Stroke(SQUIGGLE_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// The standby counter: seven blocks, [lit] of them filled, outlined in the detail's grey.
private fun DrawScope.drawBlocks(
    top: Float,
    lit: Int,
) {
    for (i in 0 until BLOCK_COUNT) {
        val at = Offset(DETAIL_X + i * BLOCK_STEP, top)
        val size = Size(BLOCK_SIZE, BLOCK_SIZE)
        if (i < lit) drawRect(StanColors.accent, at, size, style = Fill)
        drawRect(StanColors.grey, at, size, style = Stroke(BLOCK_STROKE))
    }
}
