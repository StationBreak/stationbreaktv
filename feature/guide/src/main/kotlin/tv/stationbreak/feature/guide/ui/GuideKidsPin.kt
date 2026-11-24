// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.style.TextAlign
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
import tv.stationbreak.feature.guide.R
import kotlin.math.ceil
import tv.stationbreak.core.ui.R as UiR

/** The test tag of a key of the PIN pad. */
internal fun kidsPinKeyTag(key: PadKey): String = "guide:kidsPin:key:${key.name.lowercase()}"

private val DotSize = 12.dp
private val DotGap = 12.dp
private val DotStroke = 1.5.dp
private val KeyWidth = 56.dp
private val KeyGap = 4.dp
private val SectionGap = 12.dp
private val NoticeHeight = 40.dp
private const val MILLIS_PER_MINUTE = 60_000.0

/**
 * The Kids Mode PIN pad (#789), over the Settings sheet in its place as the sign-out question is: a [LeftSheet] with
 * its
 * title (set, turn off, change), what it asks for now, a dot for each digit of the PIN (filled once typed: never the
 * digit itself), what the last answer said, the pad, and the hint row. The key under the D-pad has the guide family's
 * drawn ring; the focus is [entry]'s, not Compose's.
 *
 * A screen reader hears the sheet's root, one live region: what is asked (while no digit is typed), the key under the
 * D-pad and how many digits are typed, never which. The notice is a live region of its own, so a wrong PIN is said
 * once.
 */
@Composable
internal fun GuideKidsPin(
    entry: PinEntry,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPanelColors.current
    val title = stringResource(pinTitle(entry.purpose))
    val prompt = stringResource(pinPrompt(entry.stage))
    val keyName = pinKeyName(entry.focus)
    val entered = stringResource(R.string.guide_pin_entered, entry.digits.length, PIN_LENGTH)
    val capLine = entry.cap?.takeIf { entry.purpose == PinPurpose.Cap }?.let { stringResource(kidsCapChannels(it)) }
    // The cap line ends in its own full stop, so it joins the prompt with a space.
    val ask = listOfNotNull(capLine, prompt).joinToString(" ").takeIf { entry.digits.isEmpty() }
    val spoken = listOfNotNull(ask, keyName, entered).joinToString(". ")
    LeftSheet(
        SheetMeasures.Width,
        modifier
            .testTag(GuideTags.KIDS_PIN)
            .semantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
        scrim = false,
    ) {
        Text(
            text = title,
            modifier =
                Modifier
                    .padding(horizontal = SheetMeasures.RowTextPadding)
                    .testTag(GuideTags.KIDS_PIN_TITLE)
                    .semantics { heading() },
            style = guideText(SheetMeasures.TitleSize, FontWeight.SemiBold),
            color = colors.text,
            maxLines = 1,
        )
        capLine?.let { CapLine(it) }
        Spacer(Modifier.height(SectionGap))
        Text(
            text = prompt,
            modifier = Modifier.fillMaxWidth().testTag(GuideTags.KIDS_PIN_PROMPT).clearAndSetSemantics {},
            style = guideProse(SheetMeasures.BodySize, FontWeight.Medium),
            color = colors.text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(SectionGap))
        Dots(entry.digits.length)
        Notice(entry.notice)
        Pad(entry)
        Spacer(Modifier.weight(1f))
        KeyHintRow(
            pinHint(),
            Modifier.padding(start = SheetMeasures.RowTextPadding).testTag(GuideTags.KIDS_PIN_HINT),
        )
    }
}

// One dot for each digit of the PIN, filled once typed: the digits themselves are never drawn.
@Composable
private fun Dots(typed: Int) {
    val colors = LocalPanelColors.current
    Row(
        Modifier.fillMaxWidth().testTag(GuideTags.KIDS_PIN_DOTS).clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(DotGap, Alignment.CenterHorizontally),
    ) {
        repeat(PIN_LENGTH) { i ->
            val dot = Modifier.size(DotSize)
            Box(
                if (i < typed) {
                    dot.background(colors.text, CircleShape)
                } else {
                    dot.border(DotStroke, colors.secondary, CircleShape)
                },
            )
        }
    }
}

// What the last answer said, in a room of its own so the pad does not move when it comes and goes.
@Composable
private fun Notice(notice: PinNotice?) {
    val text = notice?.let { noticeText(it) }
    Box(Modifier.fillMaxWidth().heightIn(min = NoticeHeight).padding(vertical = KeyGap), Alignment.Center) {
        if (text != null) {
            Text(
                text = text,
                modifier =
                    Modifier
                        .testTag(GuideTags.KIDS_PIN_NOTICE)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                style = guideProse(SheetMeasures.CaptionSize, FontWeight.Normal),
                color = LocalPanelColors.current.text,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun noticeText(notice: PinNotice): String {
    val resources = LocalContext.current.resources
    return when (notice) {
        PinNotice.Mismatch -> {
            stringResource(R.string.guide_pin_mismatch)
        }

        is PinNotice.Wrong -> {
            resources.getQuantityString(R.plurals.guide_pin_wrong, notice.triesLeft, notice.triesLeft)
        }

        is PinNotice.LockedOut -> {
            val minutes = ceil(notice.remainingMillis / MILLIS_PER_MINUTE).toInt().coerceAtLeast(1)
            resources.getQuantityString(R.plurals.guide_pin_locked, minutes, minutes)
        }

        PinNotice.Failed -> {
            stringResource(R.string.guide_pin_failed)
        }
    }
}

// The pad: two rows of five digits, then Delete, and Forgot PIN? while the PIN as it is is asked for, across the foot.
@Composable
private fun Pad(entry: PinEntry) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KeyGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (row in padRows(entry.forgettable)) {
            Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                for (key in row) PadKeyCell(key, focused = key == entry.focus)
            }
        }
    }
}

// One key: a digit or Delete, with the drawn ring while it is under the D-pad, spoken as a button.
@Composable
private fun PadKeyCell(
    key: PadKey,
    focused: Boolean,
) {
    val label = pinKeyName(key)
    val width = if (key.digit == null) KeyWidth * PAD_DELETE_SPAN + KeyGap * (PAD_DELETE_SPAN - 1) else KeyWidth
    Box(
        Modifier
            .width(width)
            .heightIn(min = SheetMeasures.RowMinHeight)
            .panelFocus(focused)
            .testTag(kidsPinKeyTag(key))
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                selected = focused
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = guideText(SheetMeasures.BodySize, FontWeight.Medium),
            color = LocalPanelColors.current.text,
            maxLines = 1,
        )
    }
}

// Delete and Forgot PIN? span the pad's five columns.
private const val PAD_DELETE_SPAN = 5

@Composable
private fun pinKeyName(key: PadKey): String =
    key.digit?.toString()
        ?: stringResource(if (key == PadKey.Forgot) R.string.guide_pin_forgot else R.string.guide_pin_delete)

@StringRes
private fun pinTitle(purpose: PinPurpose): Int =
    when (purpose) {
        PinPurpose.Set -> R.string.guide_pin_title_set
        PinPurpose.TurnOff -> R.string.guide_pin_title_turn_off
        PinPurpose.Change -> R.string.guide_pin_title_change
        PinPurpose.Cap -> R.string.guide_pin_title_cap
    }

@StringRes
private fun pinPrompt(stage: PinStage): Int =
    when (stage) {
        PinStage.Current -> R.string.guide_pin_prompt_current
        PinStage.New -> R.string.guide_pin_prompt_new
        PinStage.Confirm -> R.string.guide_pin_prompt_confirm
    }

// What the keys do on the pad: OK types the key under the D-pad, Back cancels.
@Composable
private fun pinHint(): List<KeyHintPair> =
    listOf(
        KeyHintPair(
            stringResource(UiR.string.hint_key_ok),
            stringResource(R.string.guide_hint_pin_type),
            round = true,
        ),
        KeyHintPair(stringResource(UiR.string.hint_key_back), stringResource(R.string.guide_hint_pin_cancel)),
    )
