// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.KeyHintPair
import tv.stationbreak.core.ui.KeyHintRow
import tv.stationbreak.core.ui.LeftSheet
import tv.stationbreak.core.ui.LocalPanelColors
import tv.stationbreak.core.ui.RemoteKey
import tv.stationbreak.core.ui.SheetMeasures
import tv.stationbreak.core.ui.guideProse
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.core.ui.remoteKeyOf
import tv.stationbreak.core.ui.remoteKeyToAct
import tv.stationbreak.feature.guide.R
import tv.stationbreak.core.ui.R as UiR

private val SectionGap = 8.dp
private val QrGap = 12.dp
private const val URL_SP = 20

// The key of whichever of the two screens the guide draws itself is up.
internal fun SettingsPanel.screenKey(
    values: SettingsValues,
    key: Key,
    type: KeyEventType,
    repeatCount: Int,
): SettingsStep? {
    val hours = hours
    return if (hours != null) hoursKey(hours, values, key, type, repeatCount) else guideKey(key, type, repeatCount)
}

/**
 * A key while the user guide's QR screen shows (#1105): OK and Back both return to the sheet, on its row; every other
 * key is kept and does nothing. There is nothing to focus and nothing to open: no browser, no outbound intent. Null
 * for a key that is not the remote's (the media keys), as for the other screens.
 */
internal fun SettingsPanel.guideKey(
    key: Key,
    type: KeyEventType,
    repeatCount: Int,
): SettingsStep? {
    if (remoteKeyOf(key) == null) return null
    return when (remoteKeyToAct(key, type, repeatCount)) {
        RemoteKey.Back, RemoteKey.Select -> SettingsStep(copy(guideShown = false))
        else -> SettingsStep(this)
    }
}

// The code, the line that says to scan it and the address as text, the code and the text hidden from a screen reader.
@Composable
private fun QrBlock() {
    val colors = LocalPanelColors.current
    Image(
        painter = painterResource(R.drawable.guide_user_guide_qr),
        contentDescription = null,
        modifier = Modifier.testTag(GuideTags.USER_GUIDE_QR).clearAndSetSemantics { },
        contentScale = ContentScale.Fit,
    )
    Text(
        text = stringResource(R.string.guide_help_qr_prompt),
        modifier = Modifier.semantics { hideFromAccessibility() },
        style = guideProse(SheetMeasures.BodySize, FontWeight.Normal),
        color = colors.secondary,
    )
    // One line, never broken mid-address: the address is typed by anyone who cannot scan.
    Text(
        text = stringResource(R.string.guide_help_url_text),
        modifier = Modifier.testTag(GuideTags.USER_GUIDE_URL).semantics { hideFromAccessibility() },
        style = guideProse(URL_SP.sp, FontWeight.Medium),
        color = colors.text,
        softWrap = false,
        maxLines = 1,
    )
}

/**
 * The user guide screen (#1105), over the Settings sheet in its place as the hours screen is: a [LeftSheet] with the
 * title, the QR code of the public guide, a line that says to scan it and the address as text, then the hint row. The
 * code is a static drawable (`guide_user_guide_qr`, written and checked by `GuideQrTest`) on its own cream tile with
 * ink modules, drawn the same in both themes: never inverted, as many phone cameras cannot read an inverted code. It
 * is not a button and has no focus; the keys are [guideKey]'s.
 *
 * A screen reader hears one thing, the sheet's root (a live region): what the code is and its address, the code and
 * the text under it being hidden from it so nothing is read twice.
 */
@Composable
internal fun GuideUserGuide(modifier: Modifier = Modifier) {
    val colors = LocalPanelColors.current
    val spoken = stringResource(R.string.guide_help_qr_spoken)
    LeftSheet(
        SheetMeasures.Width,
        modifier
            .testTag(GuideTags.USER_GUIDE)
            .semantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
        scrim = false,
    ) {
        Text(
            text = stringResource(R.string.guide_help_qr_title),
            modifier =
                Modifier
                    .padding(horizontal = SheetMeasures.RowTextPadding)
                    .testTag(GuideTags.USER_GUIDE_TITLE)
                    .semantics { heading() },
            style = guideText(SheetMeasures.TitleSize, FontWeight.SemiBold),
            color = colors.text,
            maxLines = 1,
        )
        Spacer(Modifier.height(SectionGap))
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(QrGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            QrBlock()
        }
        val back = stringResource(UiR.string.hint_key_back)
        KeyHintRow(
            listOf(KeyHintPair(back, stringResource(UiR.string.hint_close), false, back)),
            Modifier.padding(start = SheetMeasures.RowTextPadding).testTag(GuideTags.USER_GUIDE_HINT),
        )
    }
}
