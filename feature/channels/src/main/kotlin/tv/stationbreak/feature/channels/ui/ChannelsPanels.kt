// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.LineupAccess
import tv.stationbreak.core.ui.FocusStyle
import tv.stationbreak.feature.channels.ChannelGroup
import tv.stationbreak.feature.channels.ChannelRow
import tv.stationbreak.feature.channels.ChannelsPanel
import tv.stationbreak.feature.channels.ChannelsUi
import tv.stationbreak.feature.channels.R

/** One thing the viewer can pick in a panel. [Confirm] is the panel's own yes: Switch on, Swap or Reset. */
internal enum class Choice { Watch, SwitchOn, SwitchOff, ChangeNumber, DefaultNumber, Confirm, Cancel }

/**
 * What the actions panel of [row] offers: Watch for a channel that is on, Switch on for one that is not (and can be
 * read), Switch off for one that is on, and the number changes only when the lineup can be written.
 */
internal fun actionsOf(
    row: ChannelRow,
    access: LineupAccess,
): List<Choice> =
    buildList {
        val on = row.group == ChannelGroup.On
        if (on && row.reason == null) add(Choice.Watch)
        if (access != LineupAccess.Editable) return@buildList
        if (row.kind == ChannelKind.Unusable) return@buildList
        add(if (on) Choice.SwitchOff else Choice.SwitchOn)
        add(Choice.ChangeNumber)
        if (row.hasOverride) add(Choice.DefaultNumber)
    }

/** The choices of whatever panel is open over [ui], in the order they are listed; none for the number picker. */
internal fun choicesOf(ui: ChannelsUi): List<Choice> {
    val panel = ui.panel
    return when {
        panel is ChannelsPanel.Number -> {
            emptyList()
        }

        panel is ChannelsPanel.Actions -> {
            val row = ui.rows.firstOrNull { it.id == panel.id }
            // A panel with nothing to pick still needs a way out that can be seen.
            row?.let { actionsOf(it, ui.access) }.orEmpty().ifEmpty { listOf(Choice.Cancel) }
        }

        panel != null || ui.confirmingReset -> {
            listOf(Choice.Confirm, Choice.Cancel)
        }

        else -> {
            emptyList()
        }
    }
}

/** Where the focus starts in [choicesOf]: on Cancel when the panel asks about something that is hard to take back. */
internal fun startChoice(ui: ChannelsUi): Int =
    if (ui.panel is ChannelsPanel.ConfirmOptIn || (ui.panel == null && ui.confirmingReset)) 1 else 0

/** The panel's own state, which the ViewModel does not keep: which choice is focused, and the picker's digits. */
@Immutable
internal class PanelFocus(
    start: Int,
    picker: PickerState?,
) {
    var choice by mutableIntStateOf(start)
    var picker by mutableStateOf(picker)
}

/** The dialog over the list: [ui]'s panel, or the reset question. */
@Composable
internal fun ChannelsPanelView(
    ui: ChannelsUi,
    focus: PanelFocus,
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
        val picker = focus.picker
        if (panel is ChannelsPanel.Number && picker != null) {
            NumberPicker(picker)
        } else {
            choicesOf(ui).forEachIndexed { i, choice ->
                ChoiceRow(choice, panel, focused = i == focus.choice)
            }
        }
    }
}

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
            .then(FocusStyle.drawn(focused))
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
        Choice.ChangeNumber -> ChannelsTags.ACTION_CHANGE_NUMBER
        Choice.DefaultNumber -> ChannelsTags.ACTION_DEFAULT_NUMBER
        Choice.Confirm -> ChannelsTags.ACTION_CONFIRM
        Choice.Cancel -> ChannelsTags.ACTION_CANCEL
    }
