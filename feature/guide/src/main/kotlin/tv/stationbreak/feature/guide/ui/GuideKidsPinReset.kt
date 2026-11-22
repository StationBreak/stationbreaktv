// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.kids.PinRecovery
import tv.stationbreak.core.ui.KeyHintPair
import tv.stationbreak.core.ui.KeyHintRow
import tv.stationbreak.core.ui.LeftSheet
import tv.stationbreak.core.ui.LocalPanelColors
import tv.stationbreak.core.ui.SheetMeasures
import tv.stationbreak.core.ui.guideFigures
import tv.stationbreak.core.ui.guideProse
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.feature.guide.R
import tv.stationbreak.core.ui.R as UiR

private val SectionGap = 12.dp
private val CodeSize = 44.sp
private val CodeGap = 8.dp
private val CodeHeight = 52.dp
private val StatusHeight = 72.dp

/**
 * The reset of a forgotten Kids Mode PIN (#789), over the Settings sheet in the PIN pad's place: a [LeftSheet] with its
 * title, what an administrator does with the code, the code large once the server has given one, what the request says
 * now, and the hint row. It shows what [reset] holds; the request itself is the screen's.
 *
 * A screen reader hears the title as a heading and one polite live region, the status line: with the code, digit by
 * digit, ahead of it while the code is waiting for approval, so the code is said once as it arrives.
 */
@Composable
internal fun GuideKidsPinReset(
    reset: PinReset,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPanelColors.current
    val code = (reset.recovery as? PinRecovery.Code)?.code
    val status = stringResource(statusOf(reset.recovery))
    // The code is read with a space between its digits, so a screen reader does not say it as one number.
    val spoken = code?.let { stringResource(R.string.guide_reset_code_spoken, it.toList().joinToString(" "), status) }
    LeftSheet(SheetMeasures.Width, modifier.testTag(GuideTags.KIDS_RESET), scrim = false) {
        Text(
            text = stringResource(R.string.guide_reset_title),
            modifier =
                Modifier
                    .padding(horizontal = SheetMeasures.RowTextPadding)
                    .testTag(GuideTags.KIDS_RESET_TITLE)
                    .semantics { heading() },
            style = guideText(SheetMeasures.TitleSize, FontWeight.SemiBold),
            color = colors.text,
            maxLines = 1,
        )
        Spacer(Modifier.height(SectionGap))
        Text(
            text = stringResource(R.string.guide_reset_steps),
            modifier = Modifier.padding(horizontal = SheetMeasures.RowTextPadding).testTag(GuideTags.KIDS_RESET_STEPS),
            style = guideProse(SheetMeasures.BodySize, FontWeight.Normal),
            color = colors.text,
        )
        Spacer(Modifier.height(SectionGap))
        Box(Modifier.fillMaxWidth().heightIn(min = CodeHeight), Alignment.Center) {
            if (code != null) {
                Text(
                    text = code,
                    modifier = Modifier.testTag(GuideTags.KIDS_RESET_CODE).clearAndSetSemantics {},
                    style = guideFigures(CodeSize, FontWeight.Bold).copy(letterSpacing = CodeGap.value.sp),
                    color = colors.text,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(SectionGap))
        Box(Modifier.fillMaxWidth().heightIn(min = StatusHeight), Alignment.TopCenter) {
            Text(
                text = status,
                modifier =
                    Modifier
                        .testTag(GuideTags.KIDS_RESET_STATUS)
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            spoken?.let { contentDescription = it }
                        },
                style = guideProse(SheetMeasures.BodySize, FontWeight.Medium),
                color = colors.text,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.weight(1f))
        KeyHintRow(
            resetHint(reset),
            Modifier.padding(start = SheetMeasures.RowTextPadding).testTag(GuideTags.KIDS_RESET_HINT),
        )
    }
}

// The status line for what the recovery last said: plain words for each end, and what OK does after one that failed.
@StringRes
private fun statusOf(recovery: PinRecovery?): Int =
    when (recovery) {
        null -> R.string.guide_reset_status_starting
        is PinRecovery.Code -> R.string.guide_reset_status_waiting
        PinRecovery.Done -> R.string.guide_reset_done
        PinRecovery.NoServer -> R.string.guide_reset_no_server
        PinRecovery.NotAdministrator -> R.string.guide_reset_not_administrator
        PinRecovery.Expired -> R.string.guide_reset_expired
        PinRecovery.Denied -> R.string.guide_reset_denied
        PinRecovery.Disabled -> R.string.guide_reset_disabled
        PinRecovery.Unreachable -> R.string.guide_reset_unreachable
        PinRecovery.Unavailable -> R.string.guide_reset_unavailable
    }

// What the keys do: Back cancels while the request runs, and closes once it is done; OK, once it has ended, closes
// after it worked and asks for a new code after it did not.
@Composable
private fun resetHint(reset: PinReset): List<KeyHintPair> {
    val done = reset.recovery == PinRecovery.Done
    val back =
        KeyHintPair(
            stringResource(UiR.string.hint_key_back),
            stringResource(if (done) R.string.guide_hint_reset_close else R.string.guide_hint_pin_cancel),
        )
    if (!reset.finished) return listOf(back)
    val ok =
        KeyHintPair(
            stringResource(UiR.string.hint_key_ok),
            stringResource(if (done) R.string.guide_hint_reset_close else R.string.guide_hint_reset_new_code),
            round = true,
        )
    return listOf(ok, back)
}
