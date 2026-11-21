// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.text.format.DateFormat
import androidx.annotation.StringRes
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.SyncNowState
import tv.stationbreak.core.ui.KeyCap
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
import tv.stationbreak.core.ui.teletype
import tv.stationbreak.feature.guide.R
import java.util.Date
import tv.stationbreak.core.ui.R as UiR

// The sheet's own measures, beside the shared ones in SheetMeasures.
private val ValueGap = 12.dp
private val PipGap = 4.dp
private val PipSize = 8.dp
private val PipStroke = 1.5.dp
private val FlagGap = 6.dp
private val TitleGap = 4.dp
private val SheetHintGap = 4.dp
private const val CHEVRON_SP = 20
private const val FLAG_SP = 10

// A row Kids Mode has switched off is drawn dimmer, with its reason under it.
private const val INERT_ALPHA = 0.55f

/**
 * The guide's Settings sheet (DESIGN.md "Sheet and Rows"): a [LeftSheet] over the dimmed guide, in the place of the
 * menu sheet that opened it. Its title, then its rows under teletype section flags (PICTURE, PLAYBACK, LIBRARY,
 * ACCOUNT), then the hint row for the [row] under the D-pad, pinned at the bottom while the rows scroll (and fade at an
 * edge with more beyond it) when a large font needs more room than there is.
 *
 * Every row is a label on the left and what it holds on the right: a choice row its value (from [values]) and a dot
 * for each of its values, the chosen one filled; a link a chevron; an action an OK key cap. The row under the D-pad
 * has the guide family's drawn ring ([panelFocus]); nothing scales. Under Update library a quiet line says how [sync]
 * stands.
 *
 * A screen reader is told one thing at a time, by one live region, the sheet's root: the focused row and its value,
 * and on Update library the line under it too. Nothing else in the sheet is a live region, so a change is said once.
 */
@Composable
internal fun GuideSettingsSheet(
    values: SettingsValues,
    row: SettingsRow,
    sync: SyncNowState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPanelColors.current
    val status = syncStatusText(sync)
    val spoken = spokenOf(row, values, status)
    val scroll = rememberScrollState()
    LeftSheet(
        SheetMeasures.Width,
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
                    .padding(horizontal = SheetMeasures.RowTextPadding)
                    .testTag(GuideTags.SETTINGS_TITLE)
                    .semantics { heading() },
            style = guideText(SheetMeasures.TitleSize, FontWeight.SemiBold),
            color = colors.text,
            maxLines = 1,
        )
        Spacer(Modifier.height(TitleGap))
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .scrollEdgeFade(scroll)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(SheetMeasures.RowGap),
        ) {
            for (group in SettingsGroup.entries) {
                val rows = group.rows.filter { it.isShown(values) }
                if (rows.isEmpty()) continue
                SectionFlag(group)
                for (each in rows) {
                    SettingsLine(each, values, focused = each == row)
                    when {
                        each.isInert(values) -> LockedNote(each)
                        each == SettingsRow.SyncNow && status != null -> SyncStatus(status)
                    }
                }
            }
        }
        Spacer(Modifier.height(SheetHintGap))
        SettingsHint(row, values)
    }
}

// What a screen reader is told for the focused row: its label, a choice's value, and the Update library line.
@Composable
private fun spokenOf(
    row: SettingsRow,
    values: SettingsValues,
    status: String?,
): String {
    val label = stringResource(row.label)
    val value = valueWords(row, values)?.let { stringResource(it) }
    val locked = stringResource(R.string.guide_kids_locked).takeIf { row.isInert(values) }
    val said = locked ?: status?.takeIf { row == SettingsRow.SyncNow } ?: value
    return said?.let { stringResource(R.string.guide_settings_spoken, label, it) } ?: label
}

// The line under Update library, in the device's own time format, or null when there is nothing to say.
@Composable
private fun syncStatusText(state: SyncNowState): String? {
    val context = LocalContext.current
    val format = remember(context) { DateFormat.getTimeFormat(context) }
    val line = syncNowLine(state) { ms -> format.format(Date(ms)) } ?: return null
    return line.time?.let { stringResource(line.text, it) } ?: stringResource(line.text)
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
                .padding(start = SheetMeasures.RowTextPadding, end = SheetMeasures.RowTextPadding, top = FlagGap)
                .testTag(GuideTags.settingsFlag(group))
                .clearAndSetSemantics {
                    heading()
                    contentDescription = name
                },
        style = teletype(FLAG_SP.sp),
        color = LocalPanelColors.current.flag,
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
    val colors = LocalPanelColors.current
    val label = stringResource(row.label)
    val choice = choiceShown(row, values)
    val value = valueWords(row, values)?.let { stringResource(it) }
    val inert = row.isInert(values)
    val lockedText = stringResource(R.string.guide_kids_locked)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (inert) INERT_ALPHA else 1f)
            .heightIn(min = SheetMeasures.RowMinHeight)
            .scrollIntoViewWhen(focused)
            .panelFocus(focused)
            .padding(horizontal = SheetMeasures.RowTextPadding, vertical = SheetMeasures.RowVerticalPadding)
            .testTag(GuideTags.settingsRow(row))
            .clearAndSetSemantics {
                contentDescription = label
                when {
                    inert -> {
                        role = Role.Button
                        stateDescription = lockedText
                        disabled()
                    }

                    choice != null && value != null -> {
                        stateDescription = value
                    }

                    else -> {
                        role = Role.Button
                        value?.let { stateDescription = it }
                    }
                }
                selected = focused
            },
        horizontalArrangement = Arrangement.spacedBy(ValueGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = guideProse(SheetMeasures.BodySize, FontWeight.Medium),
            color = colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            choice != null && value != null -> ChoiceValue(value, choice, row)
            value != null -> KidsValue(value)
            row.kind == RowKind.Link -> Chevron()
            else -> KeyCap(stringResource(UiR.string.hint_key_ok), round = true)
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
    val colors = LocalPanelColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(ValueGap), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = value,
            modifier = Modifier.testTag(GuideTags.settingsValue(row)),
            style = guideText(SheetMeasures.BodySize, FontWeight.Medium),
            color = colors.secondary,
            maxLines = 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(PipGap)) {
            repeat(choice.count) { i ->
                val dot = Modifier.size(PipSize)
                Box(
                    if (i == choice.index) {
                        dot.background(colors.text, CircleShape)
                    } else {
                        dot.border(PipStroke, colors.secondary, CircleShape)
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
        color = LocalPanelColors.current.secondary,
        maxLines = 1,
    )
}

// The line under Update library. Not a live region of its own: the sheet's root says it (see GuideSettingsSheet).
@Composable
private fun SyncStatus(text: String) {
    Text(
        text = text,
        modifier =
            Modifier
                .padding(horizontal = SheetMeasures.RowTextPadding)
                .testTag(GuideTags.SETTINGS_SYNC_STATUS),
        style = guideProse(SheetMeasures.CaptionSize, FontWeight.Normal),
        color = LocalPanelColors.current.secondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * One pair of the sheet's hint row, as string resources: the key's cap, what it does, whether the cap is the round
 * OK, and the key's name in words for a screen reader (the cap's own words when null).
 */
internal data class SettingsHintPair(
    @param:StringRes val key: Int,
    @param:StringRes val action: Int,
    val round: Boolean = false,
    @param:StringRes val spokenKey: Int? = null,
)

/**
 * What the keys do on [row]: on a choice Left and Right change it and OK moves it on, on a link OK opens it, on an
 * action OK does what the row says (Update, Sign out, and on Kids Mode what it does now: Set a PIN, Turn on or Turn
 * off); Back closes the sheet everywhere, last. A row Kids Mode has switched off ([SettingsRow.isInert] for [values])
 * has no
 * OK: only Back.
 */
internal fun settingsHintOf(
    row: SettingsRow,
    values: SettingsValues = SettingsValues(),
): List<SettingsHintPair> {
    val okDoes =
        when (row.kind) {
            RowKind.Choice -> R.string.guide_hint_next
            RowKind.Link -> R.string.guide_hint_open
            RowKind.Action -> actionVerb(row, values)
        }
    val ok = SettingsHintPair(UiR.string.hint_key_ok, okDoes, round = true)
    val arrows = UiR.string.hint_key_left_right
    val change = SettingsHintPair(arrows, R.string.guide_hint_change, spokenKey = UiR.string.hint_key_left_right_spoken)
    val back = SettingsHintPair(UiR.string.hint_key_back, UiR.string.hint_close)
    return when {
        row.isInert(values) -> listOf(back)
        row.kind == RowKind.Choice -> listOf(change, ok, back)
        else -> listOf(ok, back)
    }
}

// What OK does on an action row, in the row's own verb.
@StringRes
private fun actionVerb(
    row: SettingsRow,
    values: SettingsValues,
): Int =
    when {
        row == SettingsRow.SignOut -> R.string.guide_settings_sign_out
        row == SettingsRow.ChangePin -> R.string.guide_hint_change
        row != SettingsRow.KidsMode -> R.string.guide_hint_update
        values.kidsOn -> R.string.guide_hint_kids_turn_off
        values.kidsPinSet -> R.string.guide_hint_kids_turn_on
        else -> R.string.guide_hint_kids_set_pin
    }

// The hint row (DESIGN.md "Key Hints"), with key caps, read out as one line.
@Composable
private fun SettingsHint(
    row: SettingsRow,
    values: SettingsValues,
) {
    val pairs =
        settingsHintOf(row, values).map {
            val key = stringResource(it.key)
            KeyHintPair(key, stringResource(it.action), it.round, it.spokenKey?.let { id -> stringResource(id) } ?: key)
        }
    KeyHintRow(pairs, Modifier.padding(start = SheetMeasures.RowTextPadding).testTag(GuideTags.SETTINGS_HINT))
}
