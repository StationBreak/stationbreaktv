// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.DpRect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.feature.channels.ChannelsUi
import tv.stationbreak.feature.channels.ChannelsViewModel
import tv.stationbreak.feature.channels.PanelMove
import tv.stationbreak.feature.channels.R
import tv.stationbreak.feature.channels.builder.BuilderEvent
import tv.stationbreak.feature.channels.builder.BuilderMode
import tv.stationbreak.feature.channels.builder.BuilderViewModel
import tv.stationbreak.feature.channels.builder.ui.BuilderRoot

/**
 * Channel management (spec 21), drawn by its host (the guide) over live TV's picture while the screen is open: the
 * host owns whether it shows, this owns what it shows. Each showing opens a session with the focus on [current]'s
 * row: [openedAtNanos] is an opaque token for the showing (the host gives each showing its own; nothing here reads it
 * as a time), and a new token starts a new session, clean, even if the screen was never taken off the composition
 * between two showings.
 * The session closes when the screen goes.
 *
 * [pictureHole] is where live TV laid its picture, in screen dp: the screen leaves that rectangle clear. [onDone] is
 * called once, when the screen is finished: with the channel to tune for Watch, and for Back with nothing open (the
 * channel the changes made leave to tune, else null). The host closes the screen on [onDone] and tunes when `tune` is
 * non-null.
 *
 * The Guide key, Home or standby closes the guide, and this screen with it, without the closing tune [onDone] would
 * have given (accepted: nothing here ends the showing then).
 *
 * Keys: the screen moves its own focus (model-driven, not Compose focus search) with the D-pad and Select. Back closes
 * a panel, message or question first.
 *
 * New channel, Copy to customise and Edit show the channel builder (spec 22) over the list. While it shows, the keys
 * are the builder's. It ends with a Back (the list shows again, on the row or New it left from), or a save: Save and
 * watch finishes this screen with the new channel to tune, Save returns to the list on that channel's row.
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
    val builderViewModel: BuilderViewModel = hiltViewModel()
    ChannelsRoot(viewModel, builderViewModel, current, openedAtNanos, pictureHole, onDone, modifier)
}

/** [ChannelsDestination] without Hilt, so a test can bring its own ViewModel. */
@Suppress("LongParameterList") // The destination's own five, and the ViewModels a test brings.
@Composable
internal fun ChannelsRoot(
    viewModel: ChannelsViewModel,
    builderViewModel: BuilderViewModel,
    current: ChannelId?,
    openedAtNanos: Long,
    pictureHole: DpRect,
    onDone: (tune: ChannelId?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One session per showing, and per opening: the host can close and reopen the screen between two frames.
    DisposableEffect(viewModel, openedAtNanos) {
        viewModel.open(current)
        onDispose {
            viewModel.close()
            builderViewModel.close()
        }
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val keys = remember { FocusRequester() }
    val building = ui.builder != null
    // The keys are the builder's while it shows, and come back here when it goes.
    LaunchedEffect(keys, building) { if (!building) keys.requestFocus() }
    val done by rememberUpdatedState(onDone)
    val resources = LocalResources.current
    val actions = remember(viewModel, openedAtNanos) { ChannelsActions(viewModel) { done(it) } }
    // The builder is made clean for its mode before it is drawn, so it never shows an earlier visit.
    var opened by remember { mutableStateOf<BuilderMode?>(null) }
    val builderMode = ui.builder
    LaunchedEffect(builderViewModel, builderMode) {
        val mode = builderMode
        if (mode != null) openBuilder(builderViewModel, mode) { resources.getString(R.string.builder_copy_suffix, it) }
        opened = mode
    }
    Box(modifier) {
        ChannelsScreen(
            ui = ui,
            pictureHole = pictureHole,
            modifier =
                Modifier
                    .focusRequester(keys)
                    .onKeyEvent { channelsKey(it, actions::pad) }
                    .focusable(),
        )
        if (building && opened == ui.builder) {
            BuilderRoot(builderViewModel, pictureHole, onEvent = actions::builderEnded)
        }
    }
}

/** Starts a clean builder visit for [mode]; a copy's name is [copyName] of the built-in's. */
internal fun openBuilder(
    builder: BuilderViewModel,
    mode: BuilderMode,
    copyName: (String) -> String,
) {
    when (mode) {
        BuilderMode.New -> builder.openNew()
        is BuilderMode.Copy -> builder.openCopy(mode.from, copyName)
        is BuilderMode.Edit -> builder.openEdit(mode.id)
    }
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
        // The builder has the keys while it shows.
        if (ui.builder != null) return
        val inPanel = ui.panel != null || ui.confirmingReset
        when {
            pad == Pad.Back -> back(ui)
            inPanel -> panel(pad)
            else -> list(pad)
        }
    }

    /** The builder ended: a save (and watch) finishes the screen, anything else shows the list again. */
    fun builderEnded(event: BuilderEvent) {
        if (finished) return
        when (event) {
            is BuilderEvent.Saved -> vm.builderSaved(event.id, event.andWatch)?.let(::finish)
            BuilderEvent.Closed -> vm.builderClosed()
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
