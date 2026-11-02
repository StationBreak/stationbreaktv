// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import tv.stationbreak.feature.channels.ChannelsUi
import tv.stationbreak.feature.channels.ChannelsViewModel
import tv.stationbreak.feature.channels.PanelMove

/**
 * Channel management (spec 21), drawn by its host (the guide) over live TV's picture while the screen is open: the
 * host owns whether it shows, this owns what it shows. Each showing opens a session with the focus on [current]'s
 * row: [openedAtNanos] is the opening token (when the key that opened the screen arrived, `System.nanoTime`), and a
 * new token starts a new session, clean, even if the screen was never taken off the composition between two showings.
 * The session closes when the screen goes.
 *
 * [pictureHole] is where live TV laid its picture, in screen dp: the screen leaves that rectangle clear. [onDone] is
 * called once, when the screen is finished: with the channel to tune for Watch, and for Back with nothing open (the
 * channel the changes made leave to tune, else null). The host closes the screen on [onDone] and tunes when `tune` is
 * non-null.
 *
 * Keys: the screen moves its own focus (model-driven, not Compose focus search) with the D-pad and Select. Back closes
 * a panel, message or question first.
 */
@Composable
fun ChannelsDestination(
    current: ChannelId?,
    openedAtNanos: Long,
    pictureHole: DpRect,
    onDone: (tune: ChannelId?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ChannelsViewModel = hiltViewModel()
    ChannelsRoot(viewModel, current, openedAtNanos, pictureHole, onDone, modifier)
}

/** [ChannelsDestination] without Hilt, so a test can bring its own ViewModel. */
@Suppress("LongParameterList") // The destination's own five, and the ViewModel a test brings.
@Composable
internal fun ChannelsRoot(
    viewModel: ChannelsViewModel,
    current: ChannelId?,
    openedAtNanos: Long,
    pictureHole: DpRect,
    onDone: (tune: ChannelId?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One session per showing, and per opening: the host can close and reopen the screen between two frames.
    DisposableEffect(viewModel, openedAtNanos) {
        viewModel.open(current)
        onDispose { viewModel.close() }
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val keys = remember { FocusRequester() }
    LaunchedEffect(keys) { keys.requestFocus() }
    val done by rememberUpdatedState(onDone)
    val actions = remember(viewModel, openedAtNanos) { ChannelsActions(viewModel) { done(it) } }
    ChannelsScreen(
        ui = ui,
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
 * scrolls) and Select once per press. Back is acted on at its release, as the program guide's keys are:
 * a fast double Back can reach this screen as a release alone, and waiting for a down it saw would swallow that press.
 * Left unconsumed, Compose would spend it moving the focus out to live TV's root. The media keys are not the screen's.
 */
internal fun channelsKey(
    event: KeyEvent,
    pad: (Pad) -> Unit,
): Boolean {
    if (padOf(event.key) == null) return false
    padToAct(event.key, event.type, event.nativeKeyEvent.repeatCount)?.let(pad)
    return true
}

/**
 * The pad to act on for a key event, or null when the event is only consumed: Back on its release, Select on its first
 * press (never its auto-repeat), the other pads on every press (so holding one scrolls).
 */
internal fun padToAct(
    key: Key,
    type: KeyEventType,
    repeatCount: Int,
): Pad? {
    val which = padOf(key) ?: return null
    val down = type == KeyEventType.KeyDown
    return when (which) {
        Pad.Back -> which.takeIf { type == KeyEventType.KeyUp }
        Pad.Select -> which.takeIf { down && repeatCount == 0 }
        else -> which.takeIf { down }
    }
}

/**
 * What a key does, given what is open. Every decision is the ViewModel's, taken on its state at that moment, so a key
 * always acts on what the viewer saw: a second Select before the next frame does not reuse an old choice.
 */
internal class ChannelsActions(
    private val vm: ChannelsViewModel,
    private val onDone: (ChannelId?) -> Unit,
) {
    // The screen is finished once: a key that follows (a fast double Back) is ignored.
    private var finished = false

    fun pad(pad: Pad) {
        if (finished) return
        val ui = vm.ui.value
        val inPanel = ui.panel != null || ui.confirmingReset
        when {
            pad == Pad.Back -> back(ui)
            inPanel -> panel(pad)
            else -> list(pad)
        }
    }

    private fun finish(tune: ChannelId?) {
        finished = true
        onDone(tune)
    }

    private fun back(ui: ChannelsUi) {
        if (ui.panel != null || ui.confirmingReset || ui.message != null) {
            vm.dismiss()
        } else {
            finish(vm.closing())
        }
    }

    private fun list(pad: Pad) {
        when (pad) {
            Pad.Up -> vm.move(up = true)
            Pad.Down -> vm.move(up = false)
            Pad.Select -> vm.select()
            Pad.Left, Pad.Right, Pad.Back -> Unit
        }
    }

    private fun panel(pad: Pad) {
        when (pad) {
            Pad.Up -> vm.panelMove(PanelMove.Up)
            Pad.Down -> vm.panelMove(PanelMove.Down)
            Pad.Left -> vm.panelMove(PanelMove.Left)
            Pad.Right -> vm.panelMove(PanelMove.Right)
            Pad.Select -> vm.panelSelect()?.let(::finish)
            Pad.Back -> Unit
        }
    }
}
