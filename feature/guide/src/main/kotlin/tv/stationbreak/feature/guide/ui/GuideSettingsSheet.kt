// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.SyncNowState
import tv.stationbreak.core.ui.KeyCap
import tv.stationbreak.core.ui.KeyHint
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.StartSheet
import tv.stationbreak.core.ui.guideProse
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.core.ui.panelFocus
import tv.stationbreak.core.ui.scrollEdgeFade
import tv.stationbreak.core.ui.scrollIntoViewWhen
import tv.stationbreak.core.ui.teletype
import tv.stationbreak.feature.guide.R
import java.util.Date

// The sheet's measures: the menu sheet's text inset, DESIGN.md's sheet-body and sheet-caption sizes, and rows a little
// shorter than the menu sheet's, so the seven rows, four flags and the Update library line fit 486 dp (960 x 540 at
// Safe) at the font scales a TV is set to without scrolling.
private val RowMinHeight = 32.dp
private val RowTextPadding = 10.dp
private val RowVerticalPadding = 6.dp
private val RowGap = 4.dp
private val ValueGap = 12.dp
private val PipGap = 4.dp
private val PipSize = 8.dp
private val PipStroke = 1.5.dp
private val FlagGap = 6.dp
private val TitleGap = 4.dp
private val SheetHintGap = 4.dp
private val HintPairGap = 20.dp
private val HintMinHeight = 20.dp
private const val TITLE_SP = 24
private const val ROW_SP = 16
private const val CAPTION_SP = 14
private const val CHEVRON_SP = 20
private const val FLAG_SP = 10
private const val HINT_SPACE = "   "

/**
 * The guide's Settings sheet (DESIGN.md "Sheet and Rows"): a sheet on the screen's left edge over the dimmed guide, in
 * the place of the menu sheet that opened it. Its title, then its rows under teletype section flags (PICTURE,
 * PLAYBACK, LIBRARY, ACCOUNT), then the hint row for the [row] under the D-pad, pinned at the bottom while the rows
 * scroll (and fade at an edge with more beyond it) if a large font needs more room than there is.
 *
 * Every row is a label on the left and what it holds on the right: a choice row its value (from [values]) and a dot
 * for each of its values, the chosen one filled; a link a chevron; an action an OK key cap. The row under the D-pad
 * has the guide family's drawn ring ([panelFocus]); nothing scales. Under Update library a quiet line says how [sync]
 * stands. A screen reader is told the focused row and its value as they change.
 */
@Composable
internal fun GuideSettingsSheet(
    values: SettingsValues,
    row: SettingsRow,
    sync: SyncNowState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGuideColors.current
    val spoken = spokenOf(row, values)
    val scroll = rememberScrollState()
    StartSheet(
        modifier
            .testTag(GuideTags.SETTINGS_SHEET)
            .semantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Text(
            text = stringResource(R.string.guide_settings_button),
            modifier =
                Modifier
                    .padding(horizontal = RowTextPadding)
                    .testTag(GuideTags.SETTINGS_TITLE)
                    .semantics { heading() },
            style = guideText(TITLE_SP.sp, FontWeight.SemiBold),
            color = colors.textPrimary,
            maxLines = 1,
        )
        Spacer(Modifier.height(TitleGap))
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .scrollEdgeFade(scroll)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(RowGap),
        ) {
            for (group in SettingsGroup.entries) {
                SectionFlag(group)
                for (each in group.rows) {
                    SettingsLine(each, values, focused = each == row)
                    if (each == SettingsRow.SyncNow) SyncStatus(sync)
                }
            }
        }
        Spacer(Modifier.height(SheetHintGap))
        SettingsHint(row.kind)
    }
}

// What a screen reader is told for the focused row: its label, and a choice's value.
@Composable
private fun spokenOf(
    row: SettingsRow,
    values: SettingsValues,
): String {
    val label = stringResource(row.label)
    val value = choiceShown(row, values)?.let { stringResource(it.value) } ?: return label
    return stringResource(R.string.guide_settings_spoken, label, value)
}

// A section's flag (DESIGN.md "Flag"): its name in the teletype's capitals, in grey. A heading, not a row.
@Composable
private fun SectionFlag(group: SettingsGroup) {
    val name = stringResource(group.flag)
    val locale = LocalConfiguration.current.locales[0]
    Text(
        text = name.uppercase(locale),
        modifier =
            Modifier
                .padding(start = RowTextPadding, end = RowTextPadding, top = FlagGap)
                .testTag(GuideTags.settingsFlag(group))
                .clearAndSetSemantics {
                    heading()
                    contentDescription = name
                },
        style = teletype(FLAG_SP.sp),
        color = LocalGuideColors.current.textSecondary,
        maxLines = 1,
    )
}

/**
 * One row: its label, and on the right a choice's value and dots, a link's chevron or an action's OK key cap. One node
 * for a screen reader: a choice says its value as its state; a link and an action are buttons. [focused] is what
 * `selected` says, as on every row the guide family draws its own focus on.
 */
@Composable
private fun SettingsLine(
    row: SettingsRow,
    values: SettingsValues,
    focused: Boolean,
) {
    val colors = LocalGuideColors.current
    val label = stringResource(row.label)
    val choice = choiceShown(row, values)
    val value = choice?.let { stringResource(it.value) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .scrollIntoViewWhen(focused)
            .panelFocus(focused)
            .padding(horizontal = RowTextPadding, vertical = RowVerticalPadding)
            .testTag(GuideTags.settingsRow(row))
            .clearAndSetSemantics {
                contentDescription = label
                if (value != null) stateDescription = value else role = Role.Button
                selected = focused
            },
        horizontalArrangement = Arrangement.spacedBy(ValueGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = guideProse(ROW_SP.sp, FontWeight.Medium),
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            choice != null && value != null -> ChoiceValue(value, choice, row)
            row.kind == RowKind.Link -> Chevron()
            else -> KeyCap(stringResource(R.string.guide_key_ok), round = true)
        }
    }
}

// A choice's value, then one dot for each value it has, the chosen one filled: the choice is a shape, not a colour.
@Composable
private fun ChoiceValue(
    value: String,
    choice: ChoiceShown,
    row: SettingsRow,
) {
    val colors = LocalGuideColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(ValueGap), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = value,
            modifier = Modifier.testTag(GuideTags.settingsValue(row)),
            style = guideText(ROW_SP.sp, FontWeight.Medium),
            color = colors.textSecondary,
            maxLines = 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(PipGap)) {
            repeat(choice.count) { i ->
                val dot = Modifier.size(PipSize)
                Box(
                    if (i == choice.index) {
                        dot.background(colors.textPrimary, CircleShape)
                    } else {
                        dot.border(PipStroke, colors.textSecondary, CircleShape)
                    },
                )
            }
        }
    }
}

// A link's chevron: this row opens another screen.
@Composable
private fun Chevron() {
    Text(
        text = stringResource(R.string.guide_settings_chevron),
        style = guideText(CHEVRON_SP.sp, FontWeight.Medium),
        color = LocalGuideColors.current.textSecondary,
        maxLines = 1,
    )
}

// The line under Update library, in the device's own time format; a screen reader is told when it changes.
@Composable
private fun SyncStatus(state: SyncNowState) {
    val context = LocalContext.current
    val format = remember(context) { DateFormat.getTimeFormat(context) }
    val line = syncNowLine(state) { ms -> format.format(Date(ms)) } ?: return
    val text = line.time?.let { stringResource(line.text, it) } ?: stringResource(line.text)
    Text(
        text = text,
        modifier =
            Modifier
                .padding(horizontal = RowTextPadding)
                .testTag(GuideTags.SETTINGS_SYNC_STATUS)
                .semantics { liveRegion = LiveRegionMode.Polite },
        style = guideProse(CAPTION_SP.sp, FontWeight.Normal),
        color = LocalGuideColors.current.textSecondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** One pair of the sheet's hint row: the key's cap, what it does, and whether the cap is the round OK. */
internal data class SettingsHintPair(
    val key: Int,
    val action: Int,
    val round: Boolean = false,
)

/**
 * What the keys do on a row of [kind]: on a choice Left and Right change it and OK moves it on, on a link OK opens it,
 * on an action OK chooses it; Back closes the sheet everywhere, last.
 */
internal fun settingsHintOf(kind: RowKind): List<SettingsHintPair> {
    val ok =
        when (kind) {
            RowKind.Choice -> SettingsHintPair(R.string.guide_key_ok, R.string.guide_hint_next, round = true)
            RowKind.Link -> SettingsHintPair(R.string.guide_key_ok, R.string.guide_hint_open, round = true)
            RowKind.Action -> SettingsHintPair(R.string.guide_key_ok, R.string.guide_hint_choose, round = true)
        }
    val change = SettingsHintPair(R.string.guide_key_left_right, R.string.guide_hint_change)
    val back = SettingsHintPair(R.string.guide_key_back, R.string.guide_hint_close)
    return if (kind == RowKind.Choice) listOf(change, ok, back) else listOf(ok, back)
}

// The hint row (DESIGN.md "Key Hints"), with key caps, read out as one line.
@Composable
private fun SettingsHint(kind: RowKind) {
    val pairs = settingsHintOf(kind).map { Triple(stringResource(it.key), stringResource(it.action), it.round) }
    val spoken = pairs.joinToString(HINT_SPACE) { (key, action) -> "$key $action" }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = HintMinHeight)
            .padding(start = RowTextPadding)
            .testTag(GuideTags.SETTINGS_HINT)
            .clearAndSetSemantics { text = AnnotatedString(spoken) },
        horizontalArrangement = Arrangement.spacedBy(HintPairGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((key, action, round) in pairs) KeyHint(key, action, round)
    }
}
