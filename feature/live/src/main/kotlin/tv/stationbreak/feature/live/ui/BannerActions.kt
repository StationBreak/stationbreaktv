// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.stationbreak.core.ui.BroadcastColors
import tv.stationbreak.core.ui.FocusStyle
import tv.stationbreak.core.ui.ProgramCopy
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.feature.live.R

private const val PROGRESS_TRACK_ALPHA = 0.35f
private const val ACTION_FILL_ALPHA = 0.16f
private val ActionTextSize = 16.sp

/** What the two buttons of the actions banner do. */
internal class Actions(
    val info: () -> Unit,
    val last: () -> Unit,
)

/**
 * How far through its slot the program is: 6 dp, square, Ground track and Accent fill. The one-minute tick is read in
 * the draw phase and in the semantics, so it redraws the bar and nothing else.
 */
@Composable
internal fun ProgressBar(
    startMs: Long,
    endMs: Long,
    copy: ProgramCopy,
    clock: () -> Long,
) {
    val now = remember(clock) { mutableLongStateOf(clock()) }
    // On the clock's minute, when "min left" changes (#440), not a minute from whenever the banner came up.
    LaunchedEffect(clock) {
        while (true) {
            val read = clock()
            now.longValue = read
            delay(ProgramText.untilNextMinute(read))
        }
    }
    Box(
        Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .height(6.dp)
            .testTag(LiveTags.BANNER_PROGRESS)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(BannerText.progress(startMs, endMs, now.longValue), 0f..1f)
                contentDescription = copy.minutesLeft(BannerText.minutesLeft(startMs, endMs, now.longValue))
            }.drawBehind {
                drawRect(BroadcastColors.Ground.copy(alpha = PROGRESS_TRACK_ALPHA))
                val done = BannerText.progress(startMs, endMs, now.longValue)
                drawRect(BroadcastColors.Accent, size = Size(size.width * done, size.height))
            },
    )
}

/** Info, then Last (when there is a channel to go back to). Info has the focus as the row appears. */
@Composable
internal fun ActionsRow(
    hasLast: Boolean,
    actions: Actions,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    // 6 dp here plus the banner column's 4 dp spacing makes the 10 dp gap above the buttons of the UX notes.
    Row(
        Modifier.padding(top = 6.dp).testTag(LiveTags.BANNER_ACTIONS),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val info = Modifier.focusRequester(first).testTag(LiveTags.BANNER_ACTION_INFO)
        ActionButton(R.string.live_action_info, actions.info, info)
        if (hasLast) {
            ActionButton(R.string.live_action_last, actions.last, Modifier.testTag(LiveTags.BANNER_ACTION_LAST))
        }
    }
}

/**
 * A button on the Ink banner. The default container is nearly Ink and would vanish, so the colours are set: Cream at
 * 16% with Cream text, and on focus Cream with Ink text (14.9:1) in the app's one focus treatment.
 */
@Composable
private fun ActionButton(
    label: Int,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp).widthIn(min = 96.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
        colors =
            ButtonDefaults.colors(
                containerColor = BroadcastColors.Cream.copy(alpha = ACTION_FILL_ALPHA),
                contentColor = BroadcastColors.Cream,
                focusedContainerColor = BroadcastColors.Cream,
                focusedContentColor = BroadcastColors.Ink,
                pressedContainerColor = BroadcastColors.Cream,
                pressedContentColor = BroadcastColors.Ink,
            ),
        border = FocusStyle.buttonBorder(),
        scale = FocusStyle.buttonScale(),
    ) {
        val style = TextStyle(fontSize = ActionTextSize, fontWeight = FontWeight.SemiBold)
        Text(text = stringResource(label), style = style, maxLines = 1)
    }
}
