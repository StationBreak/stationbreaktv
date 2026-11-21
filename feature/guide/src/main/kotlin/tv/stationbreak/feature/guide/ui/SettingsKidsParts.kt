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
import tv.stationbreak.core.ui.LocalPanelColors
import tv.stationbreak.core.ui.SheetMeasures
import tv.stationbreak.core.ui.guideProse
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.feature.guide.R

// The Settings sheet's Kids Mode pieces (#789), apart from GuideSettingsSheet.kt: a row's words, Kids Mode's value and
// the line under a row that Kids Mode has switched off.

// What a row shows on its right as words: a choice's value, or Kids Mode's On or Off with its cap. [spoken] is the
// screen reader's way of saying it (no middle dot).
@Composable
internal fun valueText(
    row: SettingsRow,
    values: SettingsValues,
    spoken: Boolean = false,
): String? =
    if (row == SettingsRow.KidsMode) {
        kidsModeText(values, spoken)
    } else {
        choiceShown(row, values)?.let { stringResource(it.value) }
    }

// Kids Mode's value: On or Off with the cap, or a dash until the stored lock has been read.
@Composable
private fun kidsModeText(
    values: SettingsValues,
    spoken: Boolean,
): String {
    val shown = kidsModeShown(values, spoken)
    return shown.cap?.let { stringResource(shown.format, stringResource(it)) } ?: stringResource(shown.format)
}

// Kids Mode's value, On or Off, in a choice's words but with no dots: OK opens or turns it, it does not step it.
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
