// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.kids.KidsCap
import tv.stationbreak.core.domain.store.ProOffer
import tv.stationbreak.core.ui.LocalPanelColors
import tv.stationbreak.core.ui.SheetMeasures
import tv.stationbreak.core.ui.guideProse
import tv.stationbreak.feature.guide.R

// The Settings sheet's help well (#1105, #1102): what the row under the D-pad does, in a pinned, unfocusable block
// above the hint row. This file holds which words each row gets and the well itself.

// The well keeps two lines of room whatever it says, so the rows above never jump between a short and a long
// explainer; a long one (or a large font) grows it, and the rows, which scroll, give up the room.
private const val RESERVED_LINES = 2

/**
 * What the help well says for a row, as string resources: the [text] (which takes the product's name when [named]),
 * a [lead] line before it (Family Mode's cap line) and whether the row is [locked], in which case [text] is the locked
 * note and a screen reader gets no explainer.
 */
internal data class SettingsHelp(
    @param:StringRes val text: Int,
    @param:StringRes val lead: Int? = null,
    val named: Boolean = false,
    val locked: Boolean = false,
)

/**
 * What the help well says on [row] for [values]: its explainer; the locked note when Family Mode has switched the row
 * off (explaining what cannot be done is noise, and the note names no way round); nothing for Family Mode until its
 * lock has been read, so the well never states a cap that is not yet known. Family Mode's explainer follows the cap
 * now chosen ([SettingsValues.kidsCap]).
 */
internal fun settingsHelpOf(
    row: SettingsRow,
    values: SettingsValues,
): SettingsHelp? =
    when {
        row.isInert(values) -> SettingsHelp(R.string.guide_kids_locked, locked = true)
        row == SettingsRow.KidsMode -> familyHelp(values)
        row == SettingsRow.Pro && values.offer is ProOffer.Support -> SUPPORT_HELP
        else -> SettingsHelp(explainerOf(row), named = row in NAMED_EXPLAINERS)
    }

// The Support build's Pro row: a link to a page, not a purchase.
private val SUPPORT_HELP = SettingsHelp(R.string.guide_help_support, named = true)

// The explainers that take the product's name.
private val NAMED_EXPLAINERS = setOf(SettingsRow.Pro, SettingsRow.SyncNow, SettingsRow.CrashReports)

private fun familyHelp(values: SettingsValues): SettingsHelp? =
    if (values.kidsKnown) {
        val text = if (values.kidsOn) R.string.guide_help_family_on else R.string.guide_help_family_off
        SettingsHelp(text, lead = kidsCapChannels(values.kidsCap))
    } else {
        null
    }

// Each other row's explainer. Family Mode's and the Support build's Pro are [settingsHelpOf]'s own.
@StringRes
private fun explainerOf(row: SettingsRow): Int =
    when (row) {
        SettingsRow.Appearance -> R.string.guide_help_appearance
        SettingsRow.ScreenFit -> R.string.guide_help_screen_fit
        SettingsRow.ChannelChange -> R.string.guide_help_channel_change
        SettingsRow.VolumeLevelling -> R.string.guide_help_levelling
        SettingsRow.KidsMode -> R.string.guide_help_family_off
        SettingsRow.ChangePin -> R.string.guide_help_change_pin
        SettingsRow.Hours -> R.string.guide_help_hours
        SettingsRow.Libraries -> R.string.guide_help_libraries
        SettingsRow.SyncNow -> R.string.guide_help_sync_now
        SettingsRow.Pro -> R.string.guide_help_pro
        SettingsRow.SignOut -> R.string.guide_help_sign_out
        SettingsRow.CrashReports -> R.string.guide_help_crash_reports
    }

/** The line saying which channels a Family Mode [cap] keeps (the caps are cumulative). */
@StringRes
internal fun kidsCapChannels(cap: KidsCap): Int =
    when (cap) {
        KidsCap.Kids -> R.string.guide_kids_cap_tots_channels
        KidsCap.Family -> R.string.guide_kids_cap_kids_channels
        KidsCap.Teen -> R.string.guide_kids_cap_teens_channels
    }

// The product's name, from the one place it is written: the app's own label (the app-name string).
@Composable
private fun appName(): String {
    val context = LocalContext.current
    return remember(context) { context.applicationInfo.loadLabel(context.packageManager).toString() }
}

/** [help]'s words in full: its lead line, if any, then its text with the product's name where it takes one. */
@Composable
internal fun helpText(help: SettingsHelp): String {
    val body = if (help.named) stringResource(help.text, appName()) else stringResource(help.text)
    return help.lead?.let { "${stringResource(it)} $body" } ?: body
}

/**
 * The help well: [help] in the sheet's secondary text, never truncated, with room reserved for two lines at the
 * current font size. Not focusable, and hidden from a screen reader: the sheet's root says the explainer with its row,
 * so nothing is read twice. No animation: the words change as focus moves.
 */
@Composable
internal fun HelpWell(
    help: SettingsHelp?,
    modifier: Modifier = Modifier,
) {
    val style = guideProse(SheetMeasures.BodySize, FontWeight.Normal)
    val reserved = with(LocalDensity.current) { style.lineHeight.toDp() * RESERVED_LINES }
    Box(modifier.fillMaxWidth().heightIn(min = reserved).padding(horizontal = SheetMeasures.RowTextPadding)) {
        if (help != null) {
            Text(
                text = helpText(help),
                modifier = Modifier.testTag(GuideTags.SETTINGS_HELP).semantics { hideFromAccessibility() },
                style = style,
                color = LocalPanelColors.current.secondary,
            )
        }
    }
}

// What the PIN is approving: the channels of the cap being set, not the one now. The pad's description says it too.
@Composable
internal fun CapLine(text: String) {
    Text(
        text = text,
        modifier =
            Modifier
                .padding(horizontal = SheetMeasures.RowTextPadding)
                .testTag(GuideTags.KIDS_PIN_CAP_LINE)
                .semantics { hideFromAccessibility() },
        style = guideProse(SheetMeasures.CaptionSize, FontWeight.Normal),
        color = LocalPanelColors.current.secondary,
    )
}
