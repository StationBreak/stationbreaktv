// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels

import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelNumbers
import tv.stationbreak.core.domain.lineup.LineupAccess
import tv.stationbreak.core.model.ChannelId

/** One thing the viewer can pick in a panel. [Confirm] is the panel's own yes: Switch on, Swap or Reset. */
internal enum class Choice {
    Watch,
    SwitchOn,
    SwitchOff,
    CopyToCustomise,
    Edit,
    ChangeNumber,
    DefaultNumber,

    /** Opens the properties sheet: what the channel is and what feeds it. Never shown on the sheet itself. */
    About,
    Confirm,
    Cancel,
}

/**
 * What the actions panel of [row] offers: Watch for a channel that is on, Switch on for one that is not (and can be
 * read), Switch off for one that is on, and the changes (switching, the builder, numbers) only when the lineup can
 * be written, and About for every channel, last. [reasonsRead]
 * is whether the first read of why channels have nothing to air has landed. A choice that [isAvailable] refuses is not
 * listed.
 */
internal fun actionsOf(
    row: ChannelRow,
    access: LineupAccess,
    reasonsRead: Boolean,
    isPro: Boolean,
): List<Choice> =
    buildList {
        val on = row.group == ChannelGroup.On
        // Until the reasons have been read an On row may be one with nothing to air, so Watch is not offered yet.
        val watchable = reasonsRead && row.reason == null && !row.gettingReady
        if (on && watchable) add(Choice.Watch)
        addChanges(row, on, access, isPro)
        add(Choice.About)
    }

private fun MutableList<Choice>.addChanges(
    row: ChannelRow,
    on: Boolean,
    access: LineupAccess,
    isPro: Boolean,
) {
    if (access != LineupAccess.Editable || row.kind.unreadable) return
    val changes =
        buildList {
            add(if (on) Choice.SwitchOff else Choice.SwitchOn)
            // The builder works on a starter (as a copy) or a custom channel (in place); leftovers have no rules.
            if (row.kind == ChannelKind.Starter) add(Choice.CopyToCustomise)
            if (row.kind == ChannelKind.Custom) add(Choice.Edit)
            add(Choice.ChangeNumber)
            if (row.hasOverride) add(Choice.DefaultNumber)
        }
    addAll(changes.filter { isAvailable(it, row, isPro) })
}

/**
 * Whether [choice] on [row] may be offered at all (#793). Reshaping the planner's lineup (switching a built-in channel,
 * numbering it) is Pro: a free viewer is not shown those choices on a channel they did not make, and the viewer's own
 * custom channels are free. The one rule for listing a choice and for what Select does, so a hidden choice cannot be
 * reached.
 */
internal fun isAvailable(
    choice: Choice,
    row: ChannelRow,
    isPro: Boolean,
): Boolean =
    when (choice) {
        Choice.SwitchOn, Choice.SwitchOff, Choice.ChangeNumber, Choice.DefaultNumber -> isPro || row.viewerMade
        else -> true
    }

/** Whether the Reset channels line is offered: resetting the planner's lineup is Pro, so free is not shown it. */
internal fun canReset(isPro: Boolean): Boolean = isPro

/**
 * Whether the Swap panel may be offered for the number [holder] has: a free viewer swaps only with a custom
 * channel they made, so a number a built-in channel holds is just taken. A holder that is not listed counts as not
 * the viewer's.
 */
internal fun swapOffered(
    ui: ChannelsUi,
    holder: ChannelId,
): Boolean = ui.isPro || ui.rows.firstOrNull { it.id == holder }?.viewerMade == true

/**
 * The note Select on [choice] shows instead of acting, or null: Copy to customise makes a custom channel, so it is
 * held to the free cap (#793). The choice stays listed, unmarked.
 */
internal fun limitOf(
    choice: Choice,
    canAddCustom: Boolean,
): ChannelsMessage? = ChannelsMessage.CustomLimit.takeIf { choice == Choice.CopyToCustomise && !canAddCustom }

/** What the properties sheet offers for [row]: the actions panel's choices, without the one that opened the sheet. */
internal fun sheetActionsOf(
    row: ChannelRow,
    access: LineupAccess,
    reasonsRead: Boolean,
    isPro: Boolean,
): List<Choice> = actionsOf(row, access, reasonsRead, isPro).filter { it != Choice.About }

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
            row?.let { actionsOf(it, ui.access, ui.reasonsRead, ui.isPro) }.orEmpty().ifEmpty { listOf(Choice.Cancel) }
        }

        // The sheet beneath no panel: its own actions, or a way out when it has none.
        panel == null && ui.about != null -> {
            val row = ui.rows.firstOrNull { it.id == ui.about }
            val offered = row?.let { sheetActionsOf(it, ui.access, ui.reasonsRead, ui.isPro) }.orEmpty()
            offered.ifEmpty { listOf(Choice.Cancel) }
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
internal fun startChoice(
    panel: ChannelsPanel?,
    confirmingReset: Boolean,
): Int = if (panel is ChannelsPanel.ConfirmOptIn || (panel == null && confirmingReset)) 1 else 0

private const val BASE = 10

/** As many digits as the biggest channel number has. */
private val DIGITS =
    ChannelNumbers.all.last
        .toString()
        .length

/**
 * The picker's digits (as many as the biggest channel number has) and which one the D-pad is on. Nothing here is
 * Android, so it can be tested on the JVM.
 */
internal data class PickerState(
    val digits: List<Int>,
    val at: Int = 0,
) {
    /** The channel number the digits make. */
    val value: Int get() = digits.fold(0) { sum, d -> sum * BASE + d }

    /** The focused digit one more, 9 wrapping to 0. */
    fun up(): PickerState = step(1)

    /** The focused digit one less, 0 wrapping to 9. */
    fun down(): PickerState = step(BASE - 1)

    fun left(): PickerState = copy(at = (at - 1).coerceAtLeast(0))

    fun right(): PickerState = copy(at = (at + 1).coerceAtMost(DIGITS - 1))

    private fun step(by: Int): PickerState {
        val moved = digits.mapIndexed { i, d -> if (i == at) (d + by) % BASE else d }
        return copy(digits = moved)
    }

    companion object {
        /** A picker that shows [start], zero-padded to the digit count, on its first digit. */
        fun of(start: Int): PickerState {
            val n = start.coerceIn(0, ChannelNumbers.all.last)
            return PickerState(n.toString().padStart(DIGITS, '0').map { it.digitToInt() })
        }
    }
}

/** A D-pad direction inside a panel. */
internal enum class PanelMove { Up, Down, Left, Right }
