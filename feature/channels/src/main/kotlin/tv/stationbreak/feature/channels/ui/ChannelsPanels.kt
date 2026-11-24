// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.FreeTier
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.ui.mark.ChannelMark
import tv.stationbreak.core.ui.mark.markInitialsOf
import tv.stationbreak.core.ui.panelFocus
import tv.stationbreak.core.ui.scrollIntoViewWhen
import tv.stationbreak.feature.channels.ChannelRow
import tv.stationbreak.feature.channels.ChannelsPanel
import tv.stationbreak.feature.channels.ChannelsUi
import tv.stationbreak.feature.channels.Choice
import tv.stationbreak.feature.channels.ProNeed
import tv.stationbreak.feature.channels.R
import tv.stationbreak.feature.channels.choicesOf

/** The dialog over the list: [ui]'s panel, or the reset question. */
@Composable
internal fun ChannelsPanelView(
    ui: ChannelsUi,
    modifier: Modifier = Modifier,
) {
    val panel = ui.panel
    val name =
        ui.rows
            .firstOrNull { it.id == panel?.id }
            ?.name
            .orEmpty()
    Column(
        modifier
            .testTag(ChannelsTags.PANEL)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val text =
            when (panel) {
                is ChannelsPanel.Actions, is ChannelsPanel.Number -> {
                    name
                }

                is ChannelsPanel.ConfirmOptIn -> {
                    stringResource(R.string.channels_confirm_opt_in, name)
                }

                is ChannelsPanel.Swap -> {
                    stringResource(R.string.channels_number_taken, panel.number, panel.holderName)
                }

                is ChannelsPanel.NeedsPro -> {
                    proNeedText(panel.need)
                }

                null -> {
                    stringResource(R.string.channels_reset_confirm)
                }
            }
        val title = @Composable {
            Text(text, Modifier.testTag(ChannelsTags.PANEL_TEXT), style = MaterialTheme.typography.titleMedium)
        }
        if (panel is ChannelsPanel.Actions || panel is ChannelsPanel.Number) {
            // The channel's mark at the head, in the guide's 50 x 28 box.
            val markKey = ui.rows.firstOrNull { it.id == panel.id }?.markKey
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ChannelMark(markKey, markInitialsOf(name), Modifier.testTag(ChannelsTags.PANEL_MARK))
                title()
            }
        } else {
            title()
        }
        if (panel is ChannelsPanel.Actions && kindOf(ui, panel) == ChannelKind.UnusableCustom) {
            Text(
                stringResource(R.string.builder_newer_app_channel),
                Modifier.testTag(ChannelsTags.NEWER_APP),
                style = MaterialTheme.typography.bodyMedium,
                color = ChannelsPalette.secondary,
            )
        }
        val picker = ui.picker
        if (panel is ChannelsPanel.Number && picker != null) {
            NumberPicker(picker)
        } else {
            ChoiceList(ui, panel)
        }
    }
}

/** The open panel's choices: a column that scrolls inside the held-to-screen panel (see [panelMaxHeight]). */
@Composable
private fun ColumnScope.ChoiceList(
    ui: ChannelsUi,
    panel: ChannelsPanel?,
) {
    // The panel is held to the screen (see panelMaxHeight), so a long list of choices scrolls inside it, the
    // focused one kept in view; the title above it stays.
    // At least one row tall, so a large font scale cannot squeeze it to nothing.
    val rowStyle = MaterialTheme.typography.bodyLarge
    val rowHeight =
        with(LocalDensity.current) {
            rowStyle.lineHeight
                .takeIf { it.isSpecified }
                .let { it ?: rowStyle.fontSize }
                .toDp()
        } +
            ChoiceRowPadding * 2
    Column(
        Modifier.weight(1f, fill = false).heightIn(min = rowHeight).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choicesOf(ui).forEachIndexed { i, choice ->
            ChoiceRow(choice, panel, focused = i == ui.choice)
        }
    }
}

private val ChoiceRowPadding = 8.dp

private fun kindOf(
    ui: ChannelsUi,
    panel: ChannelsPanel,
): ChannelKind? = ui.rows.firstOrNull { it.id == panel.id }?.kind

@Composable
private fun ChoiceRow(
    choice: Choice,
    panel: ChannelsPanel?,
    focused: Boolean,
) {
    Text(
        labelOf(choice, panel),
        Modifier
            .fillMaxWidth()
            .scrollIntoViewWhen(focused)
            .panelFocus(focused)
            .padding(horizontal = 12.dp, vertical = ChoiceRowPadding)
            .testTag(tagOf(choice))
            .semantics { selected = focused },
        style = MaterialTheme.typography.bodyLarge,
    )
}

/** The choice's words. */
@Suppress("CyclomaticComplexMethod") // One branch for each choice.
@Composable
internal fun labelOf(
    choice: Choice,
    panel: ChannelsPanel?,
): String =
    stringResource(
        when (choice) {
            Choice.Watch -> {
                R.string.channels_action_watch
            }

            Choice.SwitchOn -> {
                R.string.channels_action_switch_on
            }

            Choice.SwitchOff -> {
                R.string.channels_action_switch_off
            }

            Choice.CopyToCustomise -> {
                R.string.builder_copy_to_customise
            }

            Choice.Edit -> {
                R.string.builder_edit
            }

            Choice.ChangeNumber -> {
                R.string.channels_action_change_number
            }

            Choice.DefaultNumber -> {
                R.string.channels_action_default_number
            }

            Choice.About -> {
                R.string.channels_action_about
            }

            // The Pro note is told, not asked: it closes with OK.
            Choice.Cancel -> {
                if (panel is ChannelsPanel.NeedsPro) R.string.channels_action_ok else R.string.channels_action_cancel
            }

            Choice.SeePro -> {
                R.string.channels_action_see_pro
            }

            Choice.Confirm -> {
                when (panel) {
                    is ChannelsPanel.ConfirmOptIn -> R.string.channels_action_switch_on
                    is ChannelsPanel.Swap -> R.string.channels_action_swap
                    else -> R.string.channels_reset_action
                }
            }
        },
    )

internal fun tagOf(choice: Choice): String =
    when (choice) {
        Choice.Watch -> ChannelsTags.ACTION_WATCH
        Choice.SwitchOn -> ChannelsTags.ACTION_SWITCH_ON
        Choice.SwitchOff -> ChannelsTags.ACTION_SWITCH_OFF
        Choice.CopyToCustomise -> ChannelsTags.ACTION_COPY
        Choice.Edit -> ChannelsTags.ACTION_EDIT
        Choice.ChangeNumber -> ChannelsTags.ACTION_CHANGE_NUMBER
        Choice.DefaultNumber -> ChannelsTags.ACTION_DEFAULT_NUMBER
        Choice.About -> ChannelsTags.ACTION_ABOUT
        Choice.Confirm -> ChannelsTags.ACTION_CONFIRM
        Choice.Cancel -> ChannelsTags.ACTION_CANCEL
        Choice.SeePro -> ChannelsTags.ACTION_SEE_PRO
    }

/** What the note says about [need], for the panel and for a screen reader. */
@Composable
internal fun proNeedText(need: ProNeed): String =
    when (need) {
        ProNeed.Reshape -> stringResource(R.string.channels_pro_needs_reshape)
        ProNeed.Reset -> stringResource(R.string.channels_pro_needs_reset)
        ProNeed.CustomLimit -> customLimitText()
        ProNeed.AdultCopy -> stringResource(R.string.channels_pro_needs_adult_copy)
    }

/** The free allowance, read from the one constant that enforces it. */
@Composable
internal fun customLimitText(): String = stringResource(R.string.pro_custom_limit_note, FreeTier.CUSTOM_CHANNELS)
