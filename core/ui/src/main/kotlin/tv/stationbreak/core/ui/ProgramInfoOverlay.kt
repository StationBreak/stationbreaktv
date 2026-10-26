// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

private const val OVERSCAN = 0.05f
private const val OVERVIEW_MAX_LINES = 5
private const val LOADING_PULSE_MS = 700
private const val LOADING_LOW_ALPHA = 0.25f
private const val DETAIL_SEPARATOR = " · "
private val PanelMaxWidth = 720.dp

/**
 * The program info panel, stateless: the channel, title, episode line and slot always; overview, year, genres and
 * runtime only when present; a pulsing bar while [loading]. With no details and not loading it simply shows the
 * always-present lines, with nothing error-like. Sits bottom-left inside the 5% safe area; the caller supplies the
 * already-formatted strings. Values are a first pass for the UX review to tune.
 */
@Suppress("LongParameterList") // Stateless display: one parameter per fact shown, as the spec 17 contract gives them.
@Composable
fun ProgramInfoOverlay(
    channel: String,
    title: String,
    episodeLine: String?,
    slot: String,
    overview: String?,
    year: Int?,
    genres: List<String>,
    runtimeMinutes: Int?,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val side = maxWidth * OVERSCAN
        val edge = maxHeight * OVERSCAN
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = side, bottom = edge)
                .width(PanelMaxWidth)
                .background(BroadcastColors.Ink)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = channel.uppercase(), style = mono(14.sp), color = BroadcastColors.Accent, maxLines = 1)
            Text(
                text = title,
                modifier = Modifier.fillMaxWidth().testTag(ProgramInfoTags.TITLE),
                style = TextStyle(fontFamily = BroadcastType.Display, fontSize = 28.sp),
                color = BroadcastColors.Cream,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            episodeLine?.let { Line(it, ProgramInfoTags.EPISODE) }
            Line(slot, ProgramInfoTags.SLOT)
            if (loading) LoadingBar()
            val details = detailLine(year, genres, runtimeMinutes)
            details?.let { Line(it, ProgramInfoTags.DETAILS) }
            overview?.let {
                Text(
                    text = it,
                    modifier = Modifier.fillMaxWidth().testTag(ProgramInfoTags.OVERVIEW),
                    style = TextStyle(fontSize = 18.sp),
                    color = BroadcastColors.Cream,
                    maxLines = OVERVIEW_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun detailLine(
    year: Int?,
    genres: List<String>,
    runtimeMinutes: Int?,
): String? {
    val parts =
        listOfNotNull(
            year?.toString(),
            genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
            runtimeMinutes?.let { stringResource(R.string.program_runtime_minutes, it) },
        )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(DETAIL_SEPARATOR)
}

@Composable
private fun Line(
    text: String,
    tag: String,
) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        style = mono(16.sp),
        color = BroadcastColors.Ground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private val MonoBold = TextStyle(fontFamily = BroadcastType.Mono, fontWeight = FontWeight.Bold)

private fun mono(size: TextUnit) = MonoBold.copy(fontSize = size)

/** A pulsing accent bar: the details are on their way. */
@Composable
private fun LoadingBar() {
    val alpha =
        rememberInfiniteTransition(label = "loading").animateFloat(
            initialValue = LOADING_LOW_ALPHA,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(LOADING_PULSE_MS), RepeatMode.Reverse),
            label = "loadingAlpha",
        )
    val description = stringResource(R.string.program_details_loading)
    Box(
        Modifier
            .width(96.dp)
            .height(4.dp)
            .testTag(ProgramInfoTags.LOADING)
            .semantics { contentDescription = description }
            .graphicsLayer { this.alpha = alpha.value }
            .background(BroadcastColors.Accent),
    )
}
