// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

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
private const val OVERVIEW_MAX_LINES = 6
private const val DETAIL_SEPARATOR = " · "
private const val PANEL_FRACTION = 0.65f
private const val SKELETON_ALPHA = 0.12f
private val SKELETON_WIDTHS = listOf(1f, 0.92f, 0.6f)
private val PanelMinWidth = 480.dp
private val PanelMaxWidth = 600.dp

/**
 * The program info panel, stateless: the channel, title, episode line and slot always; overview, year, genres and
 * runtime only when present; three still placeholder bars where the overview goes while [loading]. With no details and
 * not loading it simply shows the always-present lines, with nothing error-like. Sits bottom-left inside the 5% safe
 * area, opaque, as wide as 65% of that area but never under 480 dp or over 600 dp. It holds no focus, so its last line
 * says how to close it. The caller supplies the already-formatted strings. Read as one unit by a screen reader.
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
        val safeWidth = maxWidth - side * 2
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = side, bottom = edge)
                .width((safeWidth * PANEL_FRACTION).coerceIn(PanelMinWidth, PanelMaxWidth))
                .background(BroadcastColors.Ink)
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = channel.uppercase(),
                style = mono(16.sp, letterSpacing = 1.5.sp),
                color = BroadcastColors.Accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = title,
                modifier = Modifier.fillMaxWidth().testTag(ProgramInfoTags.TITLE),
                style = TextStyle(fontFamily = BroadcastType.Display, fontSize = 30.sp, lineHeight = 34.sp),
                color = BroadcastColors.Cream,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            episodeLine?.let { Line(it, ProgramInfoTags.EPISODE, 18.sp) }
            Line(slot, ProgramInfoTags.SLOT)
            detailLine(year, genres, runtimeMinutes)?.let { Line(it, ProgramInfoTags.DETAILS) }
            // 12 dp before the paragraph: the 8 dp between lines plus 4.
            overview?.let {
                Text(
                    text = it,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag(ProgramInfoTags.OVERVIEW),
                    style = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
                    color = BroadcastColors.Cream,
                    maxLines = OVERVIEW_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (loading && overview == null) Skeleton()
            Line(stringResource(R.string.program_back_to_close), ProgramInfoTags.CLOSE_HINT)
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
    size: TextUnit = 16.sp,
) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        style = mono(size),
        color = BroadcastColors.Ground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun mono(
    size: TextUnit,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) = TextStyle(
    fontFamily = BroadcastType.Mono,
    fontWeight = FontWeight.Bold,
    fontSize = size,
    letterSpacing = letterSpacing,
)

/**
 * Three still bars where the overview goes: the details are on their way. Nothing moves (an animation costs frames on
 * the older TV), and it is simply removed when the fetch ends, whatever the answer.
 */
@Composable
private fun Skeleton() {
    val description = stringResource(R.string.program_details_loading)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .testTag(ProgramInfoTags.LOADING)
            .semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SKELETON_WIDTHS.forEach { fraction ->
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(12.dp)
                    .background(BroadcastColors.Cream.copy(alpha = SKELETON_ALPHA)),
            )
        }
    }
}
