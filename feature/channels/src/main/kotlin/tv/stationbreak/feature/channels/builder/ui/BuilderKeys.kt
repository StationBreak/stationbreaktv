// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder.ui

import tv.stationbreak.feature.channels.PanelMove
import tv.stationbreak.feature.channels.builder.BuilderFocus
import tv.stationbreak.feature.channels.builder.BuilderUi
import tv.stationbreak.feature.channels.builder.BuilderViewModel
import tv.stationbreak.feature.channels.builder.NameRowKind
import tv.stationbreak.feature.channels.builder.SheetRow
import tv.stationbreak.feature.channels.ui.Pad

/** Whether the focus is on a line the keyboard types into: the title search and the name. */
internal fun BuilderFocus?.typesText(): Boolean =
    this == BuilderFocus.Search ||
        this == BuilderFocus.NameRow(NameRowKind.Name) ||
        this == BuilderFocus.Sheet(SheetRow.Name)

/**
 * What a key does. Every decision is the ViewModel's, taken on its state at that moment, so a key acts on what the
 * viewer saw. Select on a line that takes text opens the keyboard ([startTyping]) instead of selecting.
 */
internal class BuilderActions(
    private val vm: BuilderViewModel,
    private val startTyping: () -> Unit,
) {
    fun pad(pad: Pad) {
        val ui = vm.ui.value
        when (pad) {
            Pad.Up -> vm.move(PanelMove.Up)
            Pad.Down -> vm.move(PanelMove.Down)
            Pad.Left -> vm.move(PanelMove.Left)
            Pad.Right -> vm.move(PanelMove.Right)
            Pad.Select -> if (types(ui)) startTyping() else vm.select()
            Pad.Back -> vm.back()
        }
    }

    private fun types(ui: BuilderUi) = ui.confirm == null && !ui.saving && ui.focus.typesText()
}

/** The keyboard's part of the screen: whether the viewer is typing, where the text goes, and what ends the typing. */
internal class Typing(
    val active: Boolean,
    val onType: (String) -> Unit,
    val onDone: () -> Unit,
)
