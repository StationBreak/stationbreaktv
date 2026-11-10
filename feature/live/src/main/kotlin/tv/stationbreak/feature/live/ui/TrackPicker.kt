// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import tv.stationbreak.core.playback.StreamFacts
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.feature.live.PickerKind
import tv.stationbreak.feature.live.PickerRow
import tv.stationbreak.feature.live.TrackSelection
import tv.stationbreak.feature.live.pickerRows

private val PickerWidth = 340.dp
private val RowHeight = 32.dp
private val PickerMaxHeight = (RowHeight + 2.dp) * 8
private val RowTextSize = 12.sp
private val HintSize = 11.sp

/**
 * The picker a chip opens (design doc B2.4): the rows of [kind] for the playing [facts], in the guide's chrome, over
 * the banner. Focus starts on the playing row; Up and Down move within it and stop at its ends, so they never reach the
 * banner's buttons below (and the keys never flip the channel while it is open: `LiveKeys` leaves them to focus). OK
 * calls [onChoose] with the row's selection. Back is the screen's (`LiveCommand.DismissOverlay`), which closes it.
 */
@OptIn(ExperimentalComposeUiApi::class) // FocusProperties.exit: the picker keeps focus to itself.
@Composable
internal fun TrackPicker(
    kind: PickerKind,
    facts: StreamFacts,
    onChoose: (TrackSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGuideColors.current
    val resources = LocalContext.current.resources
    val copy = remember(resources) { StreamCopy.from(resources) }
    val rows = remember(facts, kind) { pickerRows(facts, kind) }
    val texts = remember(rows, copy) { StreamLabels.pickerTexts(rows, copy) }
    val start = rows.indexOfFirst { it.playing }.coerceAtLeast(0)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LazyColumn(
        modifier
            .width(PickerWidth)
            .heightIn(max = PickerMaxHeight)
            .chromeSurface(colors)
            .padding(6.dp)
            // Up and Down stop at the ends: the buttons under the picker are not part of it.
            .focusProperties { exit = { FocusRequester.Cancel } }
            .testTag(LiveTags.PICKER),
        state = list,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(rows) { index, row ->
            val mine = if (index == start) Modifier.focusRequester(focus) else Modifier
            val tag = mine.testTag(LiveTags.PICKER_ROW + index)
            PickerRowButton(row, texts[index], copy, { onChoose(row.selection) }, tag)
        }
    }
}

/** One row: its text, and on the right "restarts picture" when choosing it re-tunes. One merged semantics node. */
@Composable
private fun PickerRowButton(
    row: PickerRow,
    text: String,
    copy: StreamCopy,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = LocalGuideColors.current
    val description =
        buildString {
            append(text)
            if (row.playing) append(", ").append(copy.playing)
            if (row.restarts) append(", ").append(copy.restarts)
        }
    Button(
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = RowHeight)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    selected = row.playing
                },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        shape = ButtonDefaults.shape(ChromeShape),
        colors =
            ButtonDefaults.colors(
                containerColor = if (row.playing) colors.card else colors.card.copy(alpha = 0f),
                contentColor = colors.textPrimary,
                focusedContainerColor = colors.focusFill,
                focusedContentColor = colors.focusText,
                pressedContainerColor = colors.focusFill,
                pressedContentColor = colors.focusText,
            ),
        border = ButtonDefaults.border(focusedBorder = Border.None, pressedBorder = Border.None),
        scale = ButtonDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = guideText(RowTextSize, if (row.playing) FontWeight.SemiBold else FontWeight.Medium),
                // Two lines, so the suffix that tells twin rows apart is never the part that is cut off.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.restarts) {
                Spacer(Modifier.width(12.dp))
                Text(text = copy.restarts, style = guideText(HintSize, FontWeight.Normal), maxLines = 1)
            }
        }
    }
}
