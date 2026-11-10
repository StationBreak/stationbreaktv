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
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.ProgramCopy
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.feature.live.R

private val ActionTextSize = 12.sp
private val ProgressHeight = 2.dp

/** What the buttons of the actions banner do. */
internal class Actions(
    val info: () -> Unit,
    val last: () -> Unit,
    val live: () -> Unit = {},
)

/**
 * The wall clock as the banner reads it: read now, and again on each clock minute, when "min left" changes (#440), not
 * a minute from whenever the banner came up.
 */
@Composable
internal fun rememberMinuteClock(clock: () -> Long): LongState {
    val now = remember(clock) { mutableLongStateOf(clock()) }
    LaunchedEffect(clock) {
        while (true) {
            val read = clock()
            now.longValue = read
            delay(ProgramText.untilNextMinute(read))
        }
    }
    return now
}

/**
 * How far through its slot the program is: one 2 dp line along the top edge of the banner, `progressTrack` with a
 * `progressFill` fill. [now] is read in the draw phase and in the semantics, so a tick redraws the line and nothing
 * else.
 */
@Composable
internal fun ProgressLine(
    startMs: Long,
    endMs: Long,
    copy: ProgramCopy,
    now: LongState,
) {
    val colors = LocalGuideColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(ProgressHeight)
            .testTag(LiveTags.BANNER_PROGRESS)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(BannerText.progress(startMs, endMs, now.longValue), 0f..1f)
                contentDescription = copy.minutesLeft(BannerText.minutesLeft(startMs, endMs, now.longValue))
            }.drawBehind {
                drawRect(colors.progressTrack)
                val done = BannerText.progress(startMs, endMs, now.longValue)
                drawRect(colors.progressFill, size = Size(size.width * done, size.height))
            },
    )
}

/**
 * Live (while behind), Info, then Last (when there is a channel to go back to). Info has the focus as the row appears.
 * A 43 dp row under the banner's rule.
 */
@Composable
internal fun ActionsRow(
    hasLast: Boolean,
    actions: Actions,
    behind: Boolean = false,
) {
    val first = remember { FocusRequester() }
    val live = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    // The Live button comes and goes with the transport. If the row had the focus and neither Info nor Last has it
    // once Live is gone, it was Live's: Info takes it. Losses are ignored once `behind` is false, so the row's last
    // state while Live was there is what is read (the detach of a focused Live reports a loss of its own).
    val behindNow by rememberUpdatedState(behind)
    var rowFocused by remember { mutableStateOf(false) }
    var infoFocused by remember { mutableStateOf(false) }
    var lastFocused by remember { mutableStateOf(false) }
    LaunchedEffect(behind) {
        val onLive = rowFocused && !infoFocused && !lastFocused
        if (!behind && onLive) first.requestFocus()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(ACTION_ROW_HEIGHT)
            .padding(horizontal = 16.dp)
            .onFocusChanged { if (behindNow || it.hasFocus) rowFocused = it.hasFocus }
            .testTag(LiveTags.BANNER_ACTIONS),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (behind) {
            val modifier =
                Modifier
                    .focusRequester(live)
                    .focusProperties { right = first }
                    .testTag(LiveTags.BANNER_ACTION_LIVE)
            ActionButton(R.string.live_action_live, actions.live, modifier)
        }
        val info =
            Modifier
                .focusRequester(first)
                .focusProperties { if (behind) left = live }
                .onFocusChanged { infoFocused = it.hasFocus }
                .testTag(LiveTags.BANNER_ACTION_INFO)
        ActionButton(R.string.live_action_info, actions.info, info)
        if (hasLast) {
            val last = Modifier.onFocusChanged { lastFocused = it.hasFocus }.testTag(LiveTags.BANNER_ACTION_LAST)
            ActionButton(R.string.live_action_last, actions.last, last)
        }
    }
}

private val ACTION_ROW_HEIGHT = 43.dp

/**
 * A button on the banner, in the guide's focus grammar: a `card` fill, and when focused the `accent` fill with
 * `focusText`. No scale and no ring.
 */
@Composable
private fun ActionButton(
    label: Int,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = LocalGuideColors.current
    Button(
        onClick = onClick,
        modifier = modifier.height(28.dp).widthIn(min = 44.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        shape = ButtonDefaults.shape(ChromeShape),
        colors =
            ButtonDefaults.colors(
                containerColor = colors.card,
                contentColor = colors.textPrimary,
                focusedContainerColor = colors.focusFill,
                focusedContentColor = colors.focusText,
                pressedContainerColor = colors.focusFill,
                pressedContentColor = colors.focusText,
            ),
        border = ButtonDefaults.border(focusedBorder = Border.None, pressedBorder = Border.None),
        scale = ButtonDefaults.scale(focusedScale = 1f),
    ) {
        Text(text = stringResource(label), style = guideText(ActionTextSize, FontWeight.Medium), maxLines = 1)
    }
}
