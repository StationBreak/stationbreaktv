// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels

import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelNumbers
import tv.stationbreak.core.domain.lineup.LineupAccess

/** One thing the viewer can pick in a panel. [Confirm] is the panel's own yes: Switch on, Swap or Reset. */
internal enum class Choice { Watch, SwitchOn, SwitchOff, ChangeNumber, DefaultNumber, Confirm, Cancel }

/**
 * What the actions panel of [row] offers: Watch for a channel that is on, Switch on for one that is not (and can be
 * read), Switch off for one that is on, and the number changes only when the lineup can be written. [reasonsRead]
 * is whether the first read of why channels have nothing to air has landed.
 */
internal fun actionsOf(
    row: ChannelRow,
    access: LineupAccess,
    reasonsRead: Boolean,
): List<Choice> =
    buildList {
        val on = row.group == ChannelGroup.On
        // Until the reasons have been read an On row may be one with nothing to air, so Watch is not offered yet.
        if (on && reasonsRead && row.reason == null) add(Choice.Watch)
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
            row?.let { actionsOf(it, ui.access, ui.reasonsRead) }.orEmpty().ifEmpty { listOf(Choice.Cancel) }
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

/** The picker's three digits and which one the D-pad is on. Nothing here is Android, so it can be tested on the JVM. */
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
        /** A picker that shows [start], zero-padded to three digits, on its first digit. */
        fun of(start: Int): PickerState {
            val n = start.coerceIn(0, ChannelNumbers.all.last)
            return PickerState(listOf(n / (BASE * BASE), n / BASE % BASE, n % BASE))
        }
    }
}

/** A D-pad direction inside a panel. */
internal enum class PanelMove { Up, Down, Left, Right }
