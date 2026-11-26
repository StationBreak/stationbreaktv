// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.kids.KidsCap
import tv.stationbreak.core.ui.CapRow
import tv.stationbreak.core.ui.LocalPanelColors
import tv.stationbreak.core.ui.SheetMeasures
import tv.stationbreak.core.ui.guideProse
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.feature.guide.R

// The Settings sheet's Kids Mode pieces (#789), apart from GuideSettingsSheet.kt: a row's words, Kids Mode's value and
// the line under a row that Kids Mode has switched off.

// What a row shows on its right as words: a choice's value, Family Mode's On or Off (a dash until the stored lock has
// been read) or the Cap row's word.
@Composable
internal fun valueText(
    row: SettingsRow,
    values: SettingsValues,
): String? =
    when {
        row == SettingsRow.KidsMode && !values.kidsKnown -> stringResource(R.string.guide_kids_unknown)
        row == SettingsRow.KidsCap -> if (values.kidsKnown) stringResource(kidsCapWords(values.kidsCap)) else null
        else -> choiceShown(row, values)?.let { stringResource(it.value) }
    }

// Family Mode's value as a dash, until the stored lock has been read: a choice's words, with no dots (nothing to step).
@Composable
internal fun KidsValue(value: String) {
    Text(
        text = value,
        modifier = Modifier.testTag(GuideTags.settingsValue(SettingsRow.KidsMode)),
        style = guideText(SheetMeasures.BodySize, FontWeight.Medium),
        color = LocalPanelColors.current.secondary,
        maxLines = 1,
    )
}

// The Cap row (#1151): the shared segmented row, Tots | Kids | Teens, with all three words showing. Until the stored
// lock has been read it is dimmed and none is chosen, as Family Mode's value is a dash then.
@Composable
internal fun KidsCapLine(
    values: SettingsValues,
    focused: Boolean,
    modifier: Modifier,
) {
    CapRow(
        label = stringResource(SettingsRow.KidsCap.label),
        options = KidsCap.entries.map { stringResource(kidsCapWords(it)) },
        selected = KidsCap.entries.indexOf(values.kidsCap).takeIf { values.kidsKnown },
        focused = focused,
        enabled = values.kidsKnown,
        tag = GuideTags.settingsRow(SettingsRow.KidsCap),
        modifier = modifier,
        horizontalPadding = SheetMeasures.RowTextPadding,
    )
}

// The line under a row Kids Mode has switched off: why it does nothing. The sheet's root says it for the focused row.
@Composable
internal fun LockedNote(row: SettingsRow) {
    Text(
        text = stringResource(R.string.guide_kids_locked),
        modifier =
            Modifier
                .padding(horizontal = SheetMeasures.RowTextPadding)
                .testTag(GuideTags.settingsLocked(row)),
        style = guideProse(SheetMeasures.CaptionSize, FontWeight.Normal),
        color = LocalPanelColors.current.secondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
