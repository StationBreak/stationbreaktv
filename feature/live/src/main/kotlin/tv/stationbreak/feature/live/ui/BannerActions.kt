// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.stationbreak.core.playback.Route
import tv.stationbreak.core.playback.StreamFacts
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.ProgramCopy
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.feature.live.PickerKind
import tv.stationbreak.feature.live.R
import tv.stationbreak.feature.live.canPick

private val ActionTextSize = 12.sp
private val ProgressHeight = 2.dp

/** What the buttons and chips of the actions banner do. */
internal class Actions(
    val info: () -> Unit,
    val last: () -> Unit,
    val live: () -> Unit = {},
    val audio: () -> Unit = {},
    val subtitles: () -> Unit = {},
    val version: () -> Unit = {},
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
 * Live (while behind), Info, then Last (when there is a channel to go back to). Info has the focus as the row appears,
 * unless a switch that re-tuned reopened the row on [chip]: the chip used has it, and with [failed] that chip's value
 * reads "Couldn't switch" (design doc B2.7). A 43 dp row under the banner's rule.
 */
@Suppress("LongParameterList") // The buttons, what plays, where the chips sit and which picker is open.
@Composable
internal fun ActionsRow(
    hasLast: Boolean,
    actions: Actions,
    behind: Boolean = false,
    stream: StreamFacts? = null,
    chipPlaced: (PickerKind, Float) -> Unit = { _, _ -> },
    picker: PickerKind? = null,
    chip: PickerKind? = null,
    failed: Boolean = false,
    names: List<String> = emptyList(),
) {
    val first = remember { FocusRequester() }
    val live = remember { FocusRequester() }
    val chipFocus =
        remember {
            mapOf(
                PickerKind.Audio to FocusRequester(),
                PickerKind.Subtitles to FocusRequester(),
                PickerKind.Version to FocusRequester(),
            )
        }
    // Info has the focus as the row appears, unless it came up with a picker open (a remote key): the picker has it.
    FocusOnAppear(first, chip?.takeIf { stream.canPick(it) }?.let { chipFocus.getValue(it) }, picker)
    // Back, or a choice, closes the picker to the chip that opened it.
    var opened by remember { mutableStateOf<PickerKind?>(null) }
    LaunchedEffect(picker) {
        if (picker == null) opened?.let { chipFocus.getValue(it).requestFocus() }
        opened = picker
    }
    // The Live button comes and goes with the transport. If the row had the focus and neither Info nor Last has it
    // once Live is gone, it was Live's: Info takes it. Losses are ignored once `behind` is false, so the row's last
    // state while Live was there is what is read (the detach of a focused Live reports a loss of its own).
    val behindNow by rememberUpdatedState(behind)
    var rowFocused by remember { mutableStateOf(false) }
    var infoFocused by remember { mutableStateOf(false) }
    var lastFocused by remember { mutableStateOf(false) }
    InfoTakesOverFromLive(behind, first) { rowFocused && !infoFocused && !lastFocused }
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
        if (stream != null) Chips(stream, actions, chipFocus, chipPlaced, chip.takeIf { failed }, names)
    }
}

/**
 * When the Live button goes away while it had the focus ([onLive]), Info takes it. Only when Live was there: a row that
 * appears without it (a switch's reopened banner, focused on a chip) has no Live button to have lost.
 */
@Composable
private fun InfoTakesOverFromLive(
    behind: Boolean,
    info: FocusRequester,
    onLive: () -> Boolean,
) {
    val hadLive = remember { booleanArrayOf(behind) }
    val liveFocused by rememberUpdatedState(onLive)
    LaunchedEffect(behind) {
        if (hadLive[0] && !behind && liveFocused()) info.requestFocus()
        hadLive[0] = behind
    }
}

/** The focus as the row appears: the chip a switch used, else Info, unless a picker came up with it (it has it). */
@Composable
private fun FocusOnAppear(
    info: FocusRequester,
    used: FocusRequester?,
    picker: PickerKind?,
) {
    LaunchedEffect(Unit) {
        when {
            used != null -> used.requestFocus()
            picker == null -> info.requestFocus()
        }
    }
}

/** "Transcoding" at the right end of the chips row: 11 sp, `textSecondary`, never focused. */
@Composable
private fun TranscodingLabel(modifier: Modifier) {
    Text(
        text = stringResource(R.string.live_stream_transcoding),
        modifier = modifier.testTag(LiveTags.BANNER_TRANSCODING),
        style = guideText(TranscodingSize, FontWeight.Normal),
        color = LocalGuideColors.current.textSecondary,
        textAlign = TextAlign.End,
        maxLines = 1,
    )
}

private val TranscodingSize = 11.sp

/**
 * The Audio, Subtitles and Version chips (design doc B1, B2.8), after a 1 dp divider: each `Label  Value`, the value
 * the same fact the stream line states. A chip with nothing to choose from shows but takes no focus: one audio track,
 * no subtitle tracks, and one version. While the route is a transcode, the
 * row ends with a right-aligned "Transcoding".
 */
@Suppress("LongParameterList") // The stream, the actions, where chips sit, the failed chip, the titles.
@Composable
private fun RowScope.Chips(
    stream: StreamFacts,
    actions: Actions,
    focus: Map<PickerKind, FocusRequester>,
    placed: (PickerKind, Float) -> Unit,
    failed: PickerKind?,
    names: List<String>,
) {
    val context = LocalContext.current
    val copy = remember(context) { StreamCopy.from(context.resources) }
    Box(Modifier.width(1.dp).height(16.dp).background(LocalGuideColors.current.line))
    val audio = StreamLabels.audioChip(stream, copy)
    if (audio != null) {
        Chip(
            R.string.live_chip_audio,
            ChipFace(audio, failed == PickerKind.Audio),
            stream.canPick(PickerKind.Audio),
            actions.audio,
            Modifier
                .focusRequester(focus.getValue(PickerKind.Audio))
                .onGloballyPositioned { placed(PickerKind.Audio, it.positionInRoot().x) }
                .testTag(LiveTags.BANNER_CHIP_AUDIO),
        )
    }
    Chip(
        R.string.live_chip_subtitles,
        ChipFace(StreamLabels.subtitlesChip(stream, copy), failed == PickerKind.Subtitles),
        stream.canPick(PickerKind.Subtitles),
        actions.subtitles,
        Modifier
            .focusRequester(focus.getValue(PickerKind.Subtitles))
            .onGloballyPositioned { placed(PickerKind.Subtitles, it.positionInRoot().x) }
            .testTag(LiveTags.BANNER_CHIP_SUBTITLES),
    )
    StreamLabels.versionChip(stream, copy, names)?.let { version ->
        val tag =
            Modifier
                .focusRequester(focus.getValue(PickerKind.Version))
                .onGloballyPositioned { placed(PickerKind.Version, it.positionInRoot().x) }
                .testTag(LiveTags.BANNER_CHIP_VERSION)
        val face = ChipFace(version, failed == PickerKind.Version)
        Chip(R.string.live_chip_version, face, stream.canPick(PickerKind.Version), actions.version, tag)
    }
    if (stream.route == Route.TRANSCODE) TranscodingLabel(Modifier.weight(1f))
}

/** What a chip says: the value of the fact it stands for, and whether the last switch from it did not take. */
private class ChipFace(
    val value: String,
    val failed: Boolean = false,
)

/** One chip: a button when it can open a picker, else the same look with no focus and no click. */
@Composable
private fun Chip(
    label: Int,
    face: ChipFace,
    focusable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = LocalGuideColors.current
    val name = stringResource(label)
    val value = face.value
    // A switch that did not take: the chip says so in its normal text colour, and still names what plays.
    val shown = if (face.failed) stringResource(R.string.live_switch_failed) else value
    val description =
        if (face.failed) {
            stringResource(R.string.live_chip_description_failed, name, value)
        } else {
            stringResource(R.string.live_chip_description, name, value)
        }
    if (focusable) {
        val merged = modifier.semantics(mergeDescendants = true) { contentDescription = description }
        ActionButton(label, onClick, merged, shown)
    } else {
        Row(
            modifier
                .height(28.dp)
                .padding(horizontal = 12.dp)
                .semantics(mergeDescendants = true) { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dim = colors.textSecondary
            Text(text = name, style = guideText(ActionTextSize, FontWeight.Medium), color = dim, maxLines = 1)
            Text(text = value, style = guideText(ActionTextSize, FontWeight.Normal), color = dim, maxLines = 1)
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
    value: String? = null,
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
        if (value != null) {
            Text(
                text = "  $value \u2303",
                style = guideText(ActionTextSize, FontWeight.Normal),
                maxLines = 1,
            )
        }
    }
}
