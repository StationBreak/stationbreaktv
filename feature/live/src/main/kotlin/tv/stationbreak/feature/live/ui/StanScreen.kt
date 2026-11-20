// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // The copy's positions are the motion study's own, in its 1920 x 1080 stage units.

package tv.stationbreak.feature.live.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.stationbreak.core.ui.BroadcastType
import tv.stationbreak.core.ui.ChannelChangeStyle
import tv.stationbreak.core.ui.LocalScreenFit
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.stan.StanStageLayout
import tv.stationbreak.core.ui.stan.StanStageScope
import tv.stationbreak.core.ui.stan.rememberStanRenderer
import tv.stationbreak.feature.live.LiveState
import tv.stationbreak.feature.live.R
import java.util.Date

// Where the copy sits, from the motion study's `copy()` (see StanStageLayout): baselines and sizes in stage units.
private const val STAGE_WIDTH = 1920.0
private const val COLUMN_X = 1000.0
private const val DETAIL_X = 1004.0
private const val ONE_LINE_Y = 545.0
private const val TWO_LINE_Y = 470.0
private const val LINE_STEP = 150.0
private const val HEADLINE_SIZE = 156f
private const val RULE_DROP = 52.0
private const val STATUS_DROP = 124.0
private const val STATUS_SIZE = 32f
private const val STATUS_SPACING_EM = 0.125f
private const val DETAIL_DROP = 168.0
private const val DETAIL_SIZE = 28f
private const val DETAIL_SPACING_EM = 0.07f
private const val BLOCKS_DROP = 196f
private const val SIGN_OFF_SIZE = 46f
private const val CLOCK_SIZE = 28f

// The sign-off's baseline is 26 stage units above the safe area's bottom edge (80 above the stage's, which is 5% in).
private const val SIGN_OFF_RISE = 26.0

/** The project's text floor: nothing is set smaller, however small the stage is drawn (the guide's corner). */
private val TEXT_FLOOR = 10.sp
private const val MAX_FONT_SCALE = 1.2f
private const val CLOCK_TICK_MS = 10_000L

/**
 * What fills the screen for [state] when it has no picture: Stan's screen for it ([stanSpecOf], the one mapping), or
 * snow when the viewer chose [change] Snow for a change of channel. [full] is false in the guide's corner.
 */
@Composable
internal fun StationGround(
    state: LiveState,
    change: ChannelChangeStyle,
    nowMs: () -> Long,
    full: Boolean = true,
) {
    val context = LocalContext.current
    val format = remember(context) { DateFormat.getTimeFormat(context) }
    val spec = remember(state, change, full, format) { stanSpecOf(state, change, full) { format.format(Date(it)) } }
    if (spec == null) Snow() else StanScreen(spec, nowMs, full)
}

/**
 * One of Stan's station screens: the animated stage and, over it, the copy of [spec] laid out as the motion study
 * does (Ranchers line or lines, the wave rule, the teletype status, the grey detail, the standby block counter) and,
 * when [full] (the whole screen, not the guide's corner), the "Station Break" sign-off and the clock at the safe area's
 * bottom corners, where the cable banner sits over them. In the corner only the headline and the status are set: the
 * rest would be tiny, and the floor would make it overlap. [nowMs] is the clock's time.
 *
 * A screen reader is told [StanScreenSpec.spoken] once, politely, when there is one: the plain status, not Stan's
 * line. The copy itself is hidden from it, so that nothing is read twice; the tags stay.
 */
@Composable
internal fun StanScreen(
    spec: StanScreenSpec,
    nowMs: () -> Long,
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
    val frame = rememberSceneFrame(spec.scene)
    val renderer = LocalStanRenderer.current ?: rememberStanRenderer(StanInk)
    // Only a change of count reaches the art's layer: the tick does not.
    val lit = remember(frame) { derivedStateOf { frame.value.blocks } }
    Box(Modifier.fillMaxSize().testTag(spec.tag).then(live)) {
        StanStageLayout(
            frame,
            Modifier.fillMaxSize().graphicsLayer().testTag(LiveTags.STAN_STAGE),
            StanInk,
            renderer,
        ) { StanCopy(spec, lit, full, nowMs) }
    }
}

@Composable
private fun StanStageScope.StanCopy(
    spec: StanScreenSpec,
    lit: androidx.compose.runtime.State<Int?>,
    full: Boolean,
    nowMs: () -> Long,
) {
    val density = LocalDensity.current
    val screen = with(density) { DpSize(transform.width.toDp(), transform.height.toDp()) }
    val safe = ScreenLayout.safeArea(screen, LocalScreenFit.current)
    val safeLeft = transform.stageX(with(density) { safe.left.toPx() })
    val safeRight = transform.stageX(with(density) { safe.right.toPx() })
    val safeBottom = transform.stageY(with(density) { safe.bottom.toPx() })
    val lines =
        spec.headline
            .text()
            .uppercase()
            .split('\n')
    val top = if (lines.size == 2) TWO_LINE_Y else ONE_LINE_Y
    val last = top + (lines.size - 1) * LINE_STEP
    val columnWidth = (if (full) safeRight else STAGE_WIDTH) - COLUMN_X
    Spacer(
        Modifier
            .fillMaxSize()
            .stanArt(transform, (last + RULE_DROP).toFloat(), if (full) last.toFloat() + BLOCKS_DROP else null, lit),
    )
    val fit = headlineFit(lines, columnWidth)
    lines.forEachIndexed { i, line ->
        Text(
            text = line,
            modifier =
                Modifier
                    .atStageBaseline(COLUMN_X, top + i * LINE_STEP, columnWidth)
                    .semantics { hideFromAccessibility() }
                    .testTag(LiveTags.STAN_HEADLINE),
            style = copyStyle(BroadcastType.Voice, HEADLINE_SIZE * fit, FontWeight.Normal),
            color = StanInk.cream,
            maxLines = 1,
            softWrap = false,
        )
    }
    val teletype = FontWeight.Bold
    CopyLine(
        spec.status.text(),
        last + STATUS_DROP,
        columnWidth,
        LiveTags.STAN_STATUS,
        copyStyle(BroadcastType.Mono, STATUS_SIZE, teletype, STATUS_SPACING_EM),
        StanInk.accent,
    )
    if (full) {
        spec.detail?.let {
            CopyLine(
                it.text(),
                last + DETAIL_DROP,
                columnWidth,
                LiveTags.STAN_DETAIL,
                copyStyle(BroadcastType.Mono, DETAIL_SIZE, teletype, DETAIL_SPACING_EM),
                StanInk.grey,
            )
        }
        SignOffAndClock(safeLeft, safeRight, safeBottom - SIGN_OFF_RISE, nowMs)
    }
}

// A teletype line (status or detail) in capitals on one line, cut with an ellipsis rather than set over the next one.
@Suppress("LongParameterList") // Where, how wide, what tag, and how it looks: one line of copy.
@Composable
private fun StanStageScope.CopyLine(
    text: String,
    baseline: Double,
    width: Double,
    tag: String,
    style: TextStyle,
    color: androidx.compose.ui.graphics.Color,
) {
    Text(
        text = text.uppercase(),
        modifier =
            Modifier
                .atStageBaseline(DETAIL_X, baseline, width - (DETAIL_X - COLUMN_X))
                .semantics { hideFromAccessibility() }
                .testTag(tag),
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// The station's sign-off at the safe area's bottom left, the time of day at its bottom right.
@Composable
private fun StanStageScope.SignOffAndClock(
    left: Double,
    right: Double,
    baseline: Double,
    nowMs: () -> Long,
) {
    Text(
        text = stringResource(R.string.live_stan_sign_off),
        modifier =
            Modifier
                .atStageBaseline(left, baseline, right - left)
                .semantics { hideFromAccessibility() }
                .testTag(LiveTags.STAN_SIGN_OFF),
        style = copyStyle(BroadcastType.Voice, SIGN_OFF_SIZE, FontWeight.Normal),
        color = StanInk.cream,
        maxLines = 1,
    )
    Text(
        text = clockText(nowMs),
        modifier =
            Modifier
                .atStageBaseline(right, baseline, right - left, alignEnd = true)
                .semantics { hideFromAccessibility() }
                .testTag(LiveTags.STAN_CLOCK),
        style = copyStyle(BroadcastType.Mono, CLOCK_SIZE, FontWeight.Bold).copy(textAlign = TextAlign.End),
        color = StanInk.grey,
        maxLines = 1,
    )
}

/**
 * How much to shrink the headline so its longest line fits [columnWidth] stage units: 1 when it fits. The study's own
 * "WELL, THAT'S" reaches past the safe area's edge at the full size, so the headline is measured, not trusted.
 */
@Composable
private fun StanStageScope.headlineFit(
    lines: List<String>,
    columnWidth: Double,
): Float {
    val measurer: TextMeasurer = rememberTextMeasurer()
    val style = copyStyle(BroadcastType.Voice, HEADLINE_SIZE, FontWeight.Normal)
    val available = transform.length(columnWidth)
    return remember(lines, style, available) {
        val widest =
            lines.maxOf {
                measurer.measure(it, style, softWrap = false, maxLines = 1, constraints = Constraints()).size.width
            }
        if (widest <= available || widest == 0) 1f else available / widest
    }
}

/** A stage font size as the project's text: following the viewer's font scale up to a point, and never under 10 sp. */
@Composable
private fun StanStageScope.copyStyle(
    family: FontFamily,
    units: Float,
    weight: FontWeight,
    spacingEm: Float = 0f,
): TextStyle {
    val fontScale = LocalDensity.current.fontScale
    // stageSp divides the font scale out, so it is put back, held between 1 and MAX_FONT_SCALE.
    val scaled = stageSp(units.toDouble()) * fontScale.coerceIn(1f, MAX_FONT_SCALE)
    val size = if (scaled < TEXT_FLOOR) TEXT_FLOOR else scaled
    return TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = size,
        lineHeight = size,
        letterSpacing = size * spacingEm,
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
