// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.LocalMotionReduced
import tv.stationbreak.core.ui.LocalScreenFit
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.feature.live.R
import tv.stationbreak.feature.live.TransportView

private const val SHOWN_AFTER_MS = 1_000L
private const val TICK_MS = 1_000L
private const val SPIN_MS = 900
private const val FULL_TURN = 360f
private const val ARC_SWEEP = 270f
private val StripTextSize = 12.sp
private val SpinnerSize = 22.dp

/**
 * How far off live [view] is at [nowMs] ([TransportView.offsetMs]), null when under a second (a skip that reached live
 * must not flash "1 second behind").
 */
internal fun behindOffset(
    view: TransportView,
    nowMs: Long,
): OffsetParts? {
    val offsetMs = view.offsetMs(nowMs)
    return if (offsetMs < SHOWN_AFTER_MS) null else OffsetParts.of(offsetMs)
}

/**
 * The transport strip, in the guide's design: "Paused" while paused, and how far behind live (nothing when caught up)
 * in hours, minutes and seconds, following each scrub step, and a spinner while a seek is under way, over the frozen
 * frame (never snow). Drawn only while [view] says it is [TransportView.visible]. The gap grows while paused or
 * seeking, so [nowMs] is read once a second then; playing behind it holds. Pending steps freeze it at their offset.
 */
@Composable
internal fun TransportStrip(
    view: TransportView?,
    nowMs: () -> Long,
) {
    if (view == null || !view.visible) return
    val now = remember(view, nowMs) { mutableLongStateOf(nowMs()) }
    val ticking = view.seeking || view.paused
    LaunchedEffect(now, ticking) {
        while (ticking) {
            delay(TICK_MS)
            now.longValue = nowMs()
        }
    }
    val offset by remember(view) { derivedStateOf { behindOffset(view, now.longValue) } }
    val shown = offset
    if (!view.paused && shown == null && !view.seeking) return
    ChromeTheme {
        val colors = LocalGuideColors.current
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val safe = ScreenLayout.safeArea(DpSize(maxWidth, maxHeight), LocalScreenFit.current)
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = safe.left, top = safe.top)
                    .chromeSurface(colors)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                    .testTag(LiveTags.TRANSPORT),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val label = transportLabel(view.paused, shown)
                if (label != null) {
                    Text(
                        text = label,
                        color = colors.textPrimary,
                        style = guideText(StripTextSize, FontWeight.Medium),
                        maxLines = 1,
                    )
                }
                if (view.seeking) Spinner()
            }
        }
    }
}

/** The strip's words: "Paused", "Paused, 20 seconds behind", "1 minute 10 seconds behind"; null when caught up. */
@Composable
private fun transportLabel(
    paused: Boolean,
    offset: OffsetParts?,
): String? =
    when {
        offset == null -> if (paused) stringResource(R.string.live_transport_paused) else null
        paused -> stringResource(R.string.live_transport_paused_behind, offsetText(offset))
        else -> stringResource(R.string.live_transport_behind, offsetText(offset))
    }

@Composable
private fun spinnerTurn(): Float =
    rememberInfiniteTransition(label = "transport-spinner")
        .animateFloat(
            initialValue = 0f,
            targetValue = FULL_TURN,
            animationSpec = infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "turn",
        ).value

/** DESIGN(#383): placeholder. A turning arc: the app has no spinner of its own. */
@Composable
private fun Spinner() {
    val reduced = LocalMotionReduced.current
    // Held still when animations are off: the arc stays, and so do the text and the progress semantics.
    val turn = if (reduced) 0f else spinnerTurn()
    val seeking = stringResource(R.string.live_transport_seeking)
    val accent = LocalGuideColors.current.accent
    Canvas(
        Modifier
            .size(SpinnerSize)
            .testTag(LiveTags.TRANSPORT_SPINNER)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                contentDescription = seeking
            },
    ) {
        val width = 3.dp.toPx()
        drawArc(
            color = accent,
            startAngle = turn,
            sweepAngle = ARC_SWEEP,
            useCenter = false,
            topLeft = Offset(width / 2, width / 2),
            size = Size(size.width - width, size.height - width),
            style = Stroke(width),
        )
    }
}
