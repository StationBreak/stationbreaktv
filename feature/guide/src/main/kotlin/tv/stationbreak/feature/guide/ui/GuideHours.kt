// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.KeyHintPair
import tv.stationbreak.core.ui.KeyHintRow
import tv.stationbreak.core.ui.LeftSheet
import tv.stationbreak.core.ui.LocalPanelColors
import tv.stationbreak.core.ui.SheetMeasures
import tv.stationbreak.core.ui.guideProse
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.core.ui.panelFocus
import tv.stationbreak.core.ui.scrollEdgeFade
import tv.stationbreak.core.ui.scrollIntoViewWhen
import tv.stationbreak.feature.guide.R
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

private val SectionGap = 8.dp
private val RowGap = 2.dp
private val ValueGap = 12.dp
private val NoticeHeight = 36.dp

/**
 * The Hours of operation screen (#789, Pro), over the Settings sheet in its place as the PIN pad is: a [LeftSheet] with
 * its title, then by [hoursMode] for [values] the Pro notice ([HoursProGate]), the set-a-PIN-first line, or the editor:
 * the week's periods and an Add row, or the one period being edited ([HoursScreen.draft]) as steppers; what the last
 * save said; the two notes (the PIN overrides the hours; the strictest period wins) and the hint row. The row under the
 * D-pad has the guide family's drawn ring; the focus is [screen]'s, not Compose's.
 *
 * A screen reader hears the sheet's root, one live region: the row under the D-pad as its own words say it. The notice
 * is a live region of its own, so a save's answer is said once.
 */
@Composable
internal fun GuideHours(
    screen: HoursScreen,
    values: SettingsValues,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPanelColors.current
    val mode = hoursMode(values)
    val draft = screen.draft.takeIf { mode == HoursMode.Editor }
    val lines = if (draft != null) draftLines(draft) else listLines(values)
    val focused = draft?.let { it.fields.indexOf(it.field) } ?: screen.focus.coerceIn(0, lines.lastIndex)
    val title =
        when {
            draft == null -> R.string.guide_hours_title
            draft.index == null -> R.string.guide_hours_title_add
            else -> R.string.guide_hours_title_edit
        }
    val spoken = if (mode == HoursMode.Editor) lines.getOrNull(focused)?.description else null
    LeftSheet(
        SheetMeasures.Width,
        modifier
            .testTag(HoursTags.SCREEN)
            .semantics {
                spoken?.let { contentDescription = it }
                liveRegion = LiveRegionMode.Polite
            },
        scrim = false,
    ) {
        Text(
            text = stringResource(title),
            modifier =
                Modifier
                    .padding(horizontal = SheetMeasures.RowTextPadding)
                    .testTag(HoursTags.TITLE)
                    .semantics { heading() },
            style = guideText(SheetMeasures.TitleSize, FontWeight.SemiBold),
            color = colors.text,
            maxLines = 1,
        )
        Spacer(Modifier.height(SectionGap))
        when (mode) {
            HoursMode.ProRequired -> HoursProGate()
            HoursMode.NeedsPin -> HoursText(R.string.guide_hours_needs_pin, HoursTags.NEEDS_PIN)
            HoursMode.Editor -> HoursBody(screen, draft, lines, focused, values)
        }
        Spacer(Modifier.weight(1f))
        val pairs =
            hoursHintOf(screen, values).map {
                val key = stringResource(it.key)
                val spokenKey = it.spokenKey?.let { id -> stringResource(id) } ?: key
                KeyHintPair(key, stringResource(it.action), it.round, spokenKey)
            }
        KeyHintRow(pairs, Modifier.padding(start = SheetMeasures.RowTextPadding).testTag(HoursTags.HINT))
    }
}

/**
 * What the screen shows without Pro: the plain notice that hours of operation is a Pro feature.
 *
 * This is the seam for the purchase and restore screen (#789, Planning): that work replaces this body (the keys stay
 * as they are: Back closes), and builds no purchase UI before then.
 */
@Composable
internal fun HoursProGate() {
    HoursText(R.string.guide_hours_pro_required, HoursTags.PRO_REQUIRED)
}

// A paragraph in the sheet's body words.
@Composable
private fun HoursText(
    text: Int,
    tag: String,
) {
    Text(
        text = stringResource(text),
        modifier = Modifier.padding(horizontal = SheetMeasures.RowTextPadding).testTag(tag),
        style = guideProse(SheetMeasures.BodySize, FontWeight.Normal),
        color = LocalPanelColors.current.text,
    )
}

// The editor: its rows (scrolling to the focused one), what the last save said and the two notes.
@Composable
private fun ColumnScope.HoursBody(
    screen: HoursScreen,
    draft: HoursDraft?,
    lines: List<HoursLine>,
    focused: Int,
    values: SettingsValues,
) {
    val scroll = rememberScrollState()
    Column(
        Modifier
            .weight(1f, fill = false)
            .fillMaxWidth()
            .scrollEdgeFade(scroll)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        if (draft == null && values.hours.periods.isEmpty()) {
            HoursText(R.string.guide_hours_empty, HoursTags.EMPTY)
        }
        lines.forEachIndexed { i, line -> HoursRow(line, focused = i == focused) }
    }
    val notice = screen.notice?.let { stringResource(hoursNoticeWords(it)) }
    Spacer(Modifier.height(RowGap))
    Row(Modifier.fillMaxWidth().heightIn(min = NoticeHeight), verticalAlignment = Alignment.CenterVertically) {
        if (notice != null) {
            Text(
                text = notice,
                modifier =
                    Modifier
                        .padding(horizontal = SheetMeasures.RowTextPadding)
                        .testTag(HoursTags.NOTICE)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                style = guideProse(SheetMeasures.CaptionSize, FontWeight.Medium),
                color = LocalPanelColors.current.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (draft == null) {
        Note(R.string.guide_hours_pin_note, HoursTags.PIN_NOTE)
        Note(R.string.guide_hours_overlap_note, HoursTags.OVERLAP_NOTE)
    }
}

@Composable
private fun Note(
    text: Int,
    tag: String,
) {
    Text(
        text = stringResource(text),
        modifier = Modifier.padding(horizontal = SheetMeasures.RowTextPadding).testTag(tag),
        style = guideProse(SheetMeasures.CaptionSize, FontWeight.Normal),
        color = LocalPanelColors.current.secondary,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

// One row: its label on the left, its value on the right, the guide family's drawn ring while it has the D-pad.
@Composable
private fun HoursRow(
    line: HoursLine,
    focused: Boolean,
) {
    val colors = LocalPanelColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = SheetMeasures.RowMinHeight)
            .scrollIntoViewWhen(focused)
            .panelFocus(focused)
            .padding(horizontal = SheetMeasures.RowTextPadding, vertical = SheetMeasures.RowVerticalPadding)
            .testTag(line.tag)
            .clearAndSetSemantics {
                contentDescription = line.description
                role = Role.Button
                selected = focused
            },
        horizontalArrangement = Arrangement.spacedBy(ValueGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = line.label,
            modifier = Modifier.weight(1f),
            style = guideProse(SheetMeasures.BodySize, FontWeight.Medium),
            color = colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        line.value?.let {
            Text(
                text = it,
                style = guideText(SheetMeasures.BodySize, FontWeight.Medium),
                color = colors.secondary,
                maxLines = 1,
            )
        }
    }
}
