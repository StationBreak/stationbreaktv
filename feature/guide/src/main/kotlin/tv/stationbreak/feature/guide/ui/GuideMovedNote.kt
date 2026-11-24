// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.lineup.MovedChannel
import tv.stationbreak.core.ui.KeyHintPair
import tv.stationbreak.core.ui.KeyHintRow
import tv.stationbreak.core.ui.LocalPanelColors
import tv.stationbreak.core.ui.RemoteKey
import tv.stationbreak.core.ui.RightSheet
import tv.stationbreak.core.ui.SheetMeasures
import tv.stationbreak.core.ui.UP_DOWN
import tv.stationbreak.core.ui.guideFigures
import tv.stationbreak.core.ui.guideProse
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.core.ui.padToAct
import tv.stationbreak.core.ui.panelFocus
import tv.stationbreak.core.ui.remoteKeyOf
import tv.stationbreak.core.ui.scrollEdgeFade
import tv.stationbreak.feature.guide.R
import tv.stationbreak.core.ui.R as UiR

// DESIGN.md's sheet width runs from the screen's edge; the 16 dp gutter is taken off, as the properties sheet does.
private val Gutter = 16.dp
private val SheetWidth = SheetMeasures.Width - Gutter
private val RowMinHeight = SheetMeasures.RowMinHeight
private val RowGap = 4.dp
private val ValueGap = 12.dp
private val SectionGap = 12.dp
private val OkMinWidth = 72.dp

// The old and new numbers share a column that lines up under every row: wide enough for "600 → 899".
private val NumbersMinWidth = 96.dp

/** How many channel rows the list shows before it scrolls (#1107): the rest are reached with Up and Down. */
internal const val MOVED_VISIBLE_ROWS = 4

/**
 * The moved-channel note (#1107): a [RightSheet] over the dimmed guide, shown once when channels were moved up to the
 * band that fits what they show. Its title, one sentence of reason for them all, then a row for each of the [moved]
 * channels (by new number, name left and numbers right), and OK pinned under the list. OK, Select and Back all call
 * [onDone] (dismissing is the acknowledgement); Up and Down scroll the list past its first [MOVED_VISIBLE_ROWS] rows
 * while the focus stays on OK, which is drawn focused and is the sheet's one stop. It has no animation of its own, as
 * no sheet in the guide family has.
 *
 * It takes the key focus itself while it shows, and keeps every remote key, so none reaches the guide under it. A
 * screen reader hears the title, the reason and each row ("Name, was channel 47, now channel 100") once, from the
 * sheet's root, then rests on OK.
 */
@Composable
internal fun GuideMovedNote(
    moved: List<MovedChannel>,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = LocalContext.current.resources.getQuantityString(R.plurals.guide_moved_title, moved.size)
    val reason = stringResource(R.string.guide_moved_reason)
    val spokenRows = moved.map { stringResource(R.string.guide_moved_row_spoken, it.name, it.from, it.to) }
    val spoken = (listOf(title, reason) + spokenRows).joinToString(". ")
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { (RowMinHeight + RowGap).toPx() }
    val finish by rememberUpdatedState(onDone)
    // Once: a Select and a Back in the same frame are one dismissal.
    var done by remember { mutableStateOf(false) }
    val dismiss = {
        if (!done) {
            done = true
            finish()
        }
    }
    val keys = remember { FocusRequester() }
    LaunchedEffect(keys) { runCatching { keys.requestFocus() } }
    RightSheet(
        SheetWidth,
        modifier
            .testTag(GuideTags.MOVED_NOTE)
            .semantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            }.focusRequester(keys)
            .onKeyEvent {
                when (padToAct(it.key, it.type, it.nativeKeyEvent.repeatCount, UP_DOWN)) {
                    RemoteKey.Select, RemoteKey.Back -> dismiss()
                    RemoteKey.Up -> scope.launch { scroll.scrollBy(-step) }
                    RemoteKey.Down -> scope.launch { scroll.scrollBy(step) }
                    else -> Unit
                }
                // Every remote key is kept (Left and Right too), and Menu, so none reaches the guide under the sheet.
                remoteKeyOf(it.key) != null || it.key == Key.Menu
            }.focusable(),
        innerPadding = Gutter,
    ) {
        Heading(title, reason)
        MovedList(moved, spokenRows, scroll)
        Spacer(Modifier.height(SectionGap))
        OkButton(dismiss)
        Spacer(Modifier.weight(1f))
        val dismissWord = stringResource(R.string.guide_moved_hint_dismiss)
        KeyHintRow(
            listOf(KeyHintPair(stringResource(UiR.string.hint_key_back), dismissWord)),
            Modifier.padding(start = SheetMeasures.RowTextPadding).testTag(GuideTags.MOVED_HINT),
        )
    }
}

// The title, then the one sentence of reason for every row.
@Composable
private fun ColumnScope.Heading(
    title: String,
    reason: String,
) {
    val colors = LocalPanelColors.current
    Text(
        text = title,
        modifier = Modifier.testTag(GuideTags.MOVED_TITLE).semantics { heading() },
        style = guideText(SheetMeasures.TitleSize, FontWeight.SemiBold),
        color = colors.text,
        maxLines = 2,
    )
    Spacer(Modifier.height(SectionGap))
    Text(
        text = reason,
        modifier = Modifier.testTag(GuideTags.MOVED_REASON),
        style = guideProse(SheetMeasures.BodySize, FontWeight.Normal),
        color = colors.secondary,
    )
    Spacer(Modifier.height(SectionGap))
}

// The rows: as many as fit up to [MOVED_VISIBLE_ROWS], then the list scrolls, with a fade at an edge with more beyond.
@Composable
private fun ColumnScope.MovedList(
    moved: List<MovedChannel>,
    spokenRows: List<String>,
    scroll: ScrollState,
) {
    Column(
        Modifier
            .testTag(GuideTags.MOVED_LIST)
            .weight(1f, fill = false)
            .heightIn(max = (RowMinHeight + RowGap) * MOVED_VISIBLE_ROWS - RowGap)
            .scrollEdgeFade(scroll)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        moved.forEachIndexed { index, each -> MovedRow(index, each, spokenRows[index]) }
    }
}

// One channel: its name (one line, ellipsised; the whole name is in the spoken text) and its numbers. One node for a
// screen reader, with the row said in words.
@Composable
private fun MovedRow(
    index: Int,
    channel: MovedChannel,
    spoken: String,
) {
    val colors = LocalPanelColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .padding(horizontal = SheetMeasures.RowTextPadding, vertical = SheetMeasures.RowVerticalPadding)
            .testTag(GuideTags.MOVED_ROW + index)
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(ValueGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.guide_moved_row, channel.name),
            modifier = Modifier.weight(1f).testTag(GuideTags.MOVED_NAME + index),
            style = guideProse(SheetMeasures.BodySize, FontWeight.Medium),
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.guide_moved_numbers, channel.from, channel.to),
            modifier = Modifier.widthIn(min = NumbersMinWidth).testTag(GuideTags.MOVED_NUMBERS + index),
            style = guideFigures(SheetMeasures.BodySize, FontWeight.Medium),
            color = colors.text,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
        )
    }
}

// The sheet's one stop, always the focused one: the guide family's drawn ring, nothing scales. The key focus is the
// sheet's, so this takes none; it is a button for a screen reader and answers a click.
@Composable
private fun OkButton(onClick: () -> Unit) {
    val colors = LocalPanelColors.current
    Text(
        text = stringResource(R.string.guide_moved_ok),
        modifier =
            Modifier
                .testTag(GuideTags.MOVED_OK)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    selected = true
                    onClick {
                        onClick()
                        true
                    }
                }.widthIn(min = OkMinWidth)
                .heightIn(min = RowMinHeight)
                .panelFocus(true)
                .padding(horizontal = SheetMeasures.RowTextPadding)
                .wrapContentHeight(Alignment.CenterVertically),
        style = guideText(SheetMeasures.BodySize, FontWeight.SemiBold),
        color = colors.text,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}
