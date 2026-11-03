// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.ui.drawnFocus
import tv.stationbreak.feature.channels.ChannelsPanel
import tv.stationbreak.feature.channels.ChannelsUi
import tv.stationbreak.feature.channels.Choice
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

                null -> {
                    stringResource(R.string.channels_reset_confirm)
                }
            }
        Text(text, Modifier.testTag(ChannelsTags.PANEL_TEXT), style = MaterialTheme.typography.titleMedium)
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
            choicesOf(ui).forEachIndexed { i, choice ->
                ChoiceRow(choice, panel, focused = i == ui.choice)
            }
        }
    }
}

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
            .drawnFocus(focused)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(tagOf(choice))
            .semantics { selected = focused },
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun labelOf(
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

            Choice.Cancel -> {
                R.string.channels_action_cancel
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

private fun tagOf(choice: Choice): String =
    when (choice) {
        Choice.Watch -> ChannelsTags.ACTION_WATCH
        Choice.SwitchOn -> ChannelsTags.ACTION_SWITCH_ON
        Choice.SwitchOff -> ChannelsTags.ACTION_SWITCH_OFF
        Choice.CopyToCustomise -> ChannelsTags.ACTION_COPY
        Choice.Edit -> ChannelsTags.ACTION_EDIT
        Choice.ChangeNumber -> ChannelsTags.ACTION_CHANGE_NUMBER
        Choice.DefaultNumber -> ChannelsTags.ACTION_DEFAULT_NUMBER
        Choice.Confirm -> ChannelsTags.ACTION_CONFIRM
        Choice.Cancel -> ChannelsTags.ACTION_CANCEL
    }
