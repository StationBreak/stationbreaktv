// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpRect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.feature.channels.ChannelsPanel
import tv.stationbreak.feature.channels.ChannelsUi
import tv.stationbreak.feature.channels.ChannelsViewModel

/**
 * Channel management (spec 21), drawn by live TV over its picture while the screen is open: live owns whether it
 * shows, this owns what it shows. Each time it appears a session opens with the focus on [current]'s row, and it
 * closes when the screen goes.
 *
 * [pictureHole] is where live TV laid its picture, in screen dp: the screen leaves that rectangle clear. [onTune] is
 * called for Watch, and for Back when the changes made leave another channel to tune ([ChannelsViewModel.closing]);
 * [onClose] for Back with nothing to tune.
 *
 * Keys: the screen moves its own focus (model-driven, not Compose focus search) with the D-pad and Select. Back closes
 * a panel, message or question first.
 */
@Composable
fun ChannelsDestination(
    current: ChannelId?,
    pictureHole: DpRect,
    onTune: (ChannelId) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ChannelsViewModel = hiltViewModel()
    ChannelsRoot(viewModel, current, pictureHole, onTune, onClose, modifier)
}

/** [ChannelsDestination] without Hilt, so a test can bring its own ViewModel. */
@Suppress("LongParameterList") // The destination's own five, and the ViewModel a test brings.
@Composable
internal fun ChannelsRoot(
    viewModel: ChannelsViewModel,
    current: ChannelId?,
    pictureHole: DpRect,
    onTune: (ChannelId) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(viewModel) {
        viewModel.open(current)
        onDispose { viewModel.close() }
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val panelFocus =
        remember(ui.panel, ui.confirmingReset) {
            PanelFocus(startChoice(ui), (ui.panel as? ChannelsPanel.Number)?.let { PickerState.of(it.start) })
        }
    val keys = remember { FocusRequester() }
    LaunchedEffect(keys) { keys.requestFocus() }
    val actions =
        remember(viewModel, panelFocus, onTune, onClose) {
            ChannelsActions(viewModel, panelFocus, onTune, onClose)
        }
    ChannelsScreen(
        ui = ui,
        panelFocus = panelFocus,
        pictureHole = pictureHole,
        modifier =
            modifier
                .focusRequester(keys)
                .onKeyEvent { channelsKey(it, actions::pad) }
                .focusable(),
    )
}

/** A key the screen takes. */
internal enum class Pad { Up, Down, Left, Right, Select, Back }

private fun padOf(key: Key): Pad? =
    when (key) {
        Key.DirectionUp -> Pad.Up
        Key.DirectionDown -> Pad.Down
        Key.DirectionLeft -> Pad.Left
        Key.DirectionRight -> Pad.Right
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> Pad.Select
        Key.Back -> Pad.Back
        else -> null
    }

/**
 * The screen's keys, all consumed so the focus never leaves it: the D-pad (auto-repeat included, so holding Up or Down
 * scrolls) and Select once per press. Back is acted on at its release, as [tv.stationbreak.feature.guide]'s guide does:
 * a fast double Back can reach this screen as a release alone, and waiting for a down it saw would swallow that press.
 * Left unconsumed, Compose would spend it moving the focus out to live TV's root. The media keys are not the screen's.
 */
internal fun channelsKey(
    event: KeyEvent,
    pad: (Pad) -> Unit,
): Boolean {
    val which = padOf(event.key) ?: return false
    val down = event.type == KeyEventType.KeyDown
    when (which) {
        Pad.Back -> if (event.type == KeyEventType.KeyUp) pad(which)
        Pad.Select -> if (down && event.nativeKeyEvent.repeatCount == 0) pad(which)
        else -> if (down) pad(which)
    }
    return true
}

/** What a key does, given what is open: a panel's choices, the number picker, or the list. */
internal class ChannelsActions(
    private val vm: ChannelsViewModel,
    private val focus: PanelFocus,
    private val onTune: (ChannelId) -> Unit,
    private val onClose: () -> Unit,
) {
    fun pad(pad: Pad) {
        val ui = vm.ui.value
        when {
            pad == Pad.Back -> back(ui)
            ui.panel is ChannelsPanel.Number -> number(pad, ui.panel)
            ui.panel != null || ui.confirmingReset -> choices(pad, ui)
            else -> list(pad)
        }
    }

    private fun back(ui: ChannelsUi) {
        if (ui.panel != null || ui.confirmingReset || ui.message != null) {
            vm.dismiss()
            return
        }
        val tune = vm.closing()
        if (tune != null) onTune(tune) else onClose()
    }

    private fun list(pad: Pad) {
        when (pad) {
            Pad.Up -> vm.move(up = true)
            Pad.Down -> vm.move(up = false)
            Pad.Select -> vm.select()
            Pad.Left, Pad.Right, Pad.Back -> Unit
        }
    }

    private fun choices(
        pad: Pad,
        ui: ChannelsUi,
    ) {
        val choices = choicesOf(ui)
        when (pad) {
            Pad.Up, Pad.Left -> focus.choice = (focus.choice - 1).coerceAtLeast(0)
            Pad.Down, Pad.Right -> focus.choice = (focus.choice + 1).coerceAtMost(choices.lastIndex)
            Pad.Select -> choices.getOrNull(focus.choice)?.let { perform(it, ui) }
            Pad.Back -> Unit
        }
    }

    private fun number(
        pad: Pad,
        panel: ChannelsPanel.Number,
    ) {
        val picker = focus.picker ?: return
        when (pad) {
            Pad.Up -> focus.picker = picker.up()
            Pad.Down -> focus.picker = picker.down()
            Pad.Left -> focus.picker = picker.left()
            Pad.Right -> focus.picker = picker.right()
            Pad.Select -> vm.setNumber(panel.id, picker.value)
            Pad.Back -> Unit
        }
    }

    private fun perform(
        choice: Choice,
        ui: ChannelsUi,
    ) {
        val panel = ui.panel
        when (choice) {
            Choice.Cancel -> {
                vm.dismiss()
            }

            Choice.Confirm -> {
                when (panel) {
                    is ChannelsPanel.ConfirmOptIn -> vm.confirmOptIn()
                    is ChannelsPanel.Swap -> vm.swap()
                    else -> vm.reset()
                }
            }

            else -> {
                panel?.let { onChoice(choice, it.id) }
            }
        }
    }

    private fun onChoice(
        choice: Choice,
        id: ChannelId,
    ) {
        when (choice) {
            Choice.Watch -> onTune(id)
            Choice.SwitchOn -> vm.switchOn(id)
            Choice.SwitchOff -> vm.switchOff(id)
            Choice.ChangeNumber -> vm.startNumber(id)
            Choice.DefaultNumber -> vm.defaultNumber(id)
            Choice.Confirm, Choice.Cancel -> Unit
        }
    }
}
