// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // The study's load-screen copy, in stage units; each number is a size or a baseline.

package tv.stationbreak.core.ui.stan

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import tv.stationbreak.core.ui.BrandColors
import tv.stationbreak.core.ui.BroadcastType
import tv.stationbreak.core.ui.LocalMotionReduced
import tv.stationbreak.core.ui.R
import kotlin.math.roundToInt

private const val MILLIS_PER_SECOND = 1_000.0
private const val NANOS_PER_SECOND = 1_000_000_000.0

// The study's load-screen copy, in stage units (see StanStageLayout): sizes, tracking, centre and baselines.
private const val TITLE_SIZE = 150.0
private const val TAGLINE_SIZE = 28.0
private const val TAGLINE_TRACKING = 10.0
private const val CENTRE_X = 960.0
private const val TITLE_BASELINE = 880.0
private const val TAGLINE_BASELINE = 952.0

private const val STATION = "station"
private const val BREAK = "break"

/** The smallest text the app draws, whatever the screen's size. */
private val MinTextSize = 10.sp

/**
 * The opening screen: Stan dozing, waking, standing and waving as the name comes in ([StanScene.Opening]), drawn on
 * the stage with the title and tagline as real text over it. Draw it over the screen that follows; the screen can
 * already be composed and working underneath, because the opening is opaque until it dissolves away.
 *
 * It takes no key and no focus: the host owns the keys (see [stanOpeningKey]) and says [skip]. It calls [onHandOver]
 * once, and the host then lets the next screen have input, and [onDissolved] after the dissolve, when the host removes
 * it from composition.
 * - **Timing:** hand-over happens when [appReady] and the name is fully in ([OPENING_TITLE_IN] seconds into the
 *   scene, read off the same frame clock the scene is drawn from), never later, so a ready app is never held longer;
 *   an app that is late is handed over the moment it is ready, and until then the scene keeps looping.
 * - **[skip]** (a key, or a screen that has something to say): hands over at once when [appReady]. Before that there
 *   is nowhere to go, so it waits for it.
 * - **Reduced motion** ("Remove animations"): the still frame, [OPENING_REDUCED_DWELL_SECONDS] after it appeared, or
 *   when the app is ready if later; no dissolve.
 * - **Dissolve:** [OPENING_DISSOLVE_MILLIS] ms, one animated layer alpha with no recomposition per frame.
 *
 * Accessibility: one polite live region says "Station Break", once; the drawn pieces are hidden from the screen
 * reader and the opening is not focusable, so there is no second announcement.
 */
@Composable
fun StanOpening(
    appReady: Boolean,
    skip: Boolean,
    onHandOver: () -> Unit,
    onDissolved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalMotionReduced.current
    val playback = rememberStanPlayback(StanScene.Opening)
    var reducedElapsed by remember { mutableDoubleStateOf(0.0) }
    var leaving by remember { mutableStateOf(false) }
    val handOver = rememberUpdatedState(onHandOver)
    val dissolved = rememberUpdatedState(onDissolved)
    val alpha = remember { Animatable(1f) }

    // Reduced motion runs no clock: its short dwell is a delay.
    LaunchedEffect(reduced) {
        if (reduced) {
            delay((openingDwellSeconds(true) * MILLIS_PER_SECOND).toLong())
            reducedElapsed = openingDwellSeconds(true)
        }
    }
    LaunchedEffect(appReady, skip, reduced) {
        if (leaving) return@LaunchedEffect
        snapshotFlow { if (reduced) reducedElapsed else playback.clockNanos.longValue / NANOS_PER_SECOND }
            .first { openingRemainingSeconds(it, appReady, reduced, skip) == 0.0 }
        leaving = true
        handOver.value()
    }
    LaunchedEffect(leaving) {
        if (leaving) {
            val millis = openingDissolveMillis(reduced)
            if (millis > 0) alpha.animateTo(0f, tween(millis, easing = LinearEasing))
            dissolved.value()
        }
    }

    val announcement = stringResource(R.string.stan_opening_announcement)
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            .clearAndSetSemantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = announcement
            },
    ) {
        StanStageLayout(playback.frame, Modifier.fillMaxSize()) { OpeningTitle(playback.frame, this) }
    }
}

/** The name over the stage: placed by baseline as the study does, faded by the frame in the layer phase. */
@Composable
private fun OpeningTitle(
    frame: State<StanFrame>,
    scope: StanStageScope,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val transform = scope.transform
    val titleSize = scope.stageSp(TITLE_SIZE).atLeastTheFloor()
    val titleStyle = TextStyle(fontFamily = BroadcastType.Voice, fontSize = titleSize, lineHeight = titleSize)
    val spaceWidth = remember(titleStyle, density) { measurer.measure(" ", titleStyle).size.width }

    // The name: "Station" then "Break" on one baseline, centred on the stage's middle, each with its own alpha.
    Layout(
        content = {
            BasicText(
                "Station",
                Modifier.layoutId(STATION),
                style = titleStyle.copy(color = BrandColors.Cream),
                maxLines = 1,
                softWrap = false,
            )
            BasicText(
                "Break",
                Modifier.layoutId(BREAK),
                style = titleStyle.copy(color = BrandColors.Orange),
                maxLines = 1,
                softWrap = false,
            )
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val station = measurables.first { it.layoutId == STATION }.measure(loose)
        val rest = measurables.first { it.layoutId == BREAK }.measure(loose)
        val baseline = station[FirstBaseline]
        val left = transform.x(CENTRE_X).roundToInt() - (station.width + spaceWidth + rest.width) / 2
        layout(constraints.maxWidth, constraints.maxHeight) {
            val title = frame.value.title ?: return@layout
            val fade = frame.value.fade
            val top = transform.y(TITLE_BASELINE + title.offsetY).roundToInt() - baseline
            station.placeWithLayer(left, top) { alpha = (title.nameStationAlpha * fade).toFloat() }
            rest.placeWithLayer(left + station.width + spaceWidth, top) {
                alpha = (title.nameBreakAlpha * fade).toFloat()
            }
        }
    }
    OpeningTagline(frame, scope)
}

/** The tagline, centred, its baseline at the study's 952. */
@Composable
private fun OpeningTagline(
    frame: State<StanFrame>,
    scope: StanStageScope,
) {
    val transform = scope.transform
    val size = scope.stageSp(TAGLINE_SIZE).atLeastTheFloor()
    val style =
        TextStyle(
            fontFamily = BroadcastType.Mono,
            fontWeight = FontWeight.Bold,
            fontSize = size,
            lineHeight = size,
            letterSpacing = scope.stageSp(TAGLINE_TRACKING),
            color = BrandColors.Grey,
        )
    val tagline = stringResource(R.string.stan_opening_tagline).uppercase()
    Layout(
        content = { BasicText(tagline, style = style, maxLines = 1, softWrap = false) },
    ) { measurables, constraints ->
        val text = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val baseline = text[FirstBaseline]
        layout(constraints.maxWidth, constraints.maxHeight) {
            val title = frame.value.title ?: return@layout
            val x = transform.x(CENTRE_X).roundToInt() - text.width / 2
            val y = transform.y(TAGLINE_BASELINE).roundToInt() - baseline
            text.placeWithLayer(x, y) { alpha = (title.taglineAlpha * frame.value.fade).toFloat() }
        }
    }
}

private fun TextUnit.atLeastTheFloor(): TextUnit = if (this < MinTextSize) MinTextSize else this
