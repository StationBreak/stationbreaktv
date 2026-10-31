// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.stationbreak.core.ui.BroadcastColors
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.feature.live.R
import tv.stationbreak.feature.live.TransportView

private const val MINUTE_MS = 60_000L
private const val SHOWN_AFTER_MS = 1_000L
private const val TICK_MS = 1_000L
private const val SPIN_MS = 900
private const val FULL_TURN = 360f
private const val ARC_SWEEP = 270f
private val StripTextSize = 18.sp
private val SpinnerSize = 22.dp

/**
 * Whole minutes [view] is behind live at [nowMs], at least 1; null when under a second behind (a skip that reached live
 * must not flash "1 min behind").
 */
internal fun behindMinutes(
    view: TransportView,
    nowMs: Long,
): Int? {
    val behind = view.behindMs(nowMs)
    return if (behind < SHOWN_AFTER_MS) null else (behind / MINUTE_MS).toInt().coerceAtLeast(1)
}

/**
 * DESIGN(#383): placeholder. The transport strip: "Paused" while paused, otherwise how many minutes behind live
 * (nothing when caught up), and a spinner while a seek is under way, over the frozen frame (never snow). Drawn only while [view]
 * says it is [TransportView.visible]; the final look belongs to the design. Paused, the label does not change; playing
 * behind, the gap holds; only while seeking does the gap grow, so [nowMs] is read once a second then, not otherwise.
 */
@Composable
internal fun TransportStrip(
    view: TransportView?,
    nowMs: () -> Long,
) {
    if (view == null || !view.visible) return
    val now = remember(view, nowMs) { mutableLongStateOf(nowMs()) }
    val ticking = view.seeking && !view.paused
    LaunchedEffect(now, ticking) {
        while (ticking) {
            delay(TICK_MS)
            now.longValue = nowMs()
        }
    }
    val minutes by remember(view) { derivedStateOf { behindMinutes(view, now.longValue) } }
    val shown = minutes
    if (!view.paused && shown == null && !view.seeking) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val safe = ScreenLayout.safeArea(DpSize(maxWidth, maxHeight))
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = safe.left, top = safe.top)
                .background(BroadcastColors.Ink)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .testTag(LiveTags.TRANSPORT),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label =
                when {
                    view.paused -> stringResource(R.string.live_transport_paused)
                    shown != null -> pluralStringResource(R.plurals.live_transport_behind, shown, shown)
                    else -> null
                }
            if (label != null) {
                Text(
                    text = label,
                    color = BroadcastColors.Cream,
                    style = TextStyle(fontSize = StripTextSize, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
            }
            if (view.seeking) Spinner()
        }
    }
}

/** DESIGN(#383): placeholder. A turning arc: the app has no spinner of its own. */
@Composable
private fun Spinner() {
    val turn by rememberInfiniteTransition(label = "transport-spinner")
        .animateFloat(
            initialValue = 0f,
            targetValue = FULL_TURN,
            animationSpec = infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "turn",
        )
    val seeking = stringResource(R.string.live_transport_seeking)
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
            color = BroadcastColors.Accent,
            startAngle = turn,
            sweepAngle = ARC_SWEEP,
            useCenter = false,
            topLeft = Offset(width / 2, width / 2),
            size = Size(size.width - width, size.height - width),
            style = Stroke(width),
        )
    }
}
