// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.RemoteKey
import tv.stationbreak.core.ui.remoteKeyOf
import tv.stationbreak.core.ui.remoteKeyToAct
import tv.stationbreak.feature.channels.ChannelsUi
import tv.stationbreak.feature.channels.ChannelsViewModel
import tv.stationbreak.feature.channels.PanelMove
import tv.stationbreak.feature.channels.R
import tv.stationbreak.feature.channels.builder.BuilderEvent
import tv.stationbreak.feature.channels.builder.BuilderMode
import tv.stationbreak.feature.channels.builder.BuilderViewModel
import tv.stationbreak.feature.channels.builder.ui.BuilderRoot
import tv.stationbreak.feature.channels.choicesOf
import tv.stationbreak.feature.channels.dialogOpen

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
    about: ChannelId? = null,
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
    // The properties sheet's info column scrolls; the keys scroll it from the ends of its actions.
    val sheetScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val stepPx = with(LocalDensity.current) { SHEET_SCROLL_STEP.toPx() }
    val actions =
        remember(viewModel, openedAtNanos) {
            ChannelsActions(
                viewModel,
                sheetOnly = about != null,
                scrollSheet = { down ->
                    scope.launch { sheetScroll.animateScrollBy(if (down) stepPx else -stepPx) }
                },
            ) { done(it) }
        }
    SheetSession(about, ui, viewModel, actions, openedAtNanos)
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
            ui = ui.beneathBuilder(),
            pictureHole = pictureHole,
            sheetOnly = about != null,
            sheetScroll = sheetScroll,
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

/**
 * A showing that is only the properties sheet of [about] (#631), over the guide: it opens the sheet once that channel
 * is in the list, and finishes the screen when the sheet goes (Back, or the channel leaving the lineup).
 */
@Composable
private fun SheetSession(
    about: ChannelId?,
    ui: ChannelsUi,
    viewModel: ChannelsViewModel,
    actions: ChannelsActions,
    openedAtNanos: Long,
) {
    if (about == null) return
    var shown by remember(viewModel, openedAtNanos) { mutableStateOf(false) }
    val listed = ui.rows.any { it.id == about }
    LaunchedEffect(about, listed) { if (listed && !shown) viewModel.openAbout(about) }
    val open = ui.about != null
    LaunchedEffect(open) {
        if (open) {
            shown = true
        } else if (shown) {
            actions.end()
        }
    }
    // A channel the list does not hold (it left the lineup meanwhile) has no sheet to show: the screen is over, rather
    // than an invisible one holding the keys.
    LaunchedEffect(ui.listed, listed) { if (ui.listed && !listed && !shown) actions.end() }
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

/** How far one Up or Down at the end of the sheet's actions scrolls its info column. */
private val SHEET_SCROLL_STEP = 48.dp

/** A key the screen takes: the remote key every TV screen shares (#823). */
internal typealias Pad = RemoteKey

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
    if (remoteKeyOf(event.key) == null) return false
    padToAct(event.key, event.type, event.nativeKeyEvent.repeatCount)?.let(pad)
    return true
}

/**
 * The pad to act on for a key event, or null when the event is only consumed: the shared remote policy (#823), with
 * every direction a pad here (Left and Right move between the sheet's columns and the builder's panels).
 */
internal fun padToAct(
    key: Key,
    type: KeyEventType,
    repeatCount: Int,
): Pad? = remoteKeyToAct(key, type, repeatCount)

/**
 * What a key does, given what is open. Every decision is the ViewModel's, taken on its state at that moment, so a key
 * always acts on what the viewer saw: a second Select before the next frame does not reuse an old choice.
 */
internal class ChannelsActions(
    private val vm: ChannelsViewModel,
    private val sheetOnly: Boolean = false,
    private val scrollSheet: (down: Boolean) -> Unit = {},
    private val onDone: (ChannelId?) -> Unit,
) {
    // The screen is finished once: a key that follows (a fast double Back) is ignored.
    private var finished = false

    fun pad(pad: Pad) {
        if (finished) return
        val ui = vm.ui.value
        // The builder has the keys while it shows.
        if (ui.builder != null || waitingForTheSheet(ui, pad)) return
        val inPanel = ui.dialogOpen
        when {
            pad == Pad.Back -> back(ui)
            inPanel -> panel(pad)
            else -> list(pad)
        }
    }

    // The list is not drawn over the guide: until the sheet is up only Back does anything, so a key never acts on a row
    // nobody can see.
    private fun waitingForTheSheet(
        ui: ChannelsUi,
        pad: Pad,
    ) = sheetOnly && ui.about == null && pad != Pad.Back

    /** The builder ended: a save (and watch) finishes the screen, anything else shows the list again. */
    fun builderEnded(event: BuilderEvent) {
        if (finished) return
        when (event) {
            is BuilderEvent.Saved -> vm.builderSaved(event.id, event.andWatch)?.let(::finish)
            BuilderEvent.Closed -> vm.builderClosed()
        }
    }

    /** The screen is over (its sheet has gone): finished as Back would, with what the changes leave to tune. */
    fun end() {
        if (!finished) finish(vm.closing())
    }

    private fun finish(tune: ChannelId?) {
        finished = true
        onDone(tune)
    }

    private fun back(ui: ChannelsUi) {
        // Over the sheet only, Back closes what is on it first, then ends the screen.
        val onTheSheet = ui.panel != null || ui.confirmingReset || ui.message != null
        if (if (sheetOnly) onTheSheet else ui.dialogOpen || ui.message != null) {
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

    // On the properties sheet (no panel over it), Up from the first action and Down from the last have nowhere to move
    // the choice, so they scroll the sheet's info column instead.
    private fun sheetEdge(
        ui: ChannelsUi,
        pad: Pad,
    ): Boolean {
        val onSheet = ui.about != null && ui.panel == null && !ui.confirmingReset
        val edge =
            onSheet &&
                when (pad) {
                    Pad.Up -> ui.choice <= 0
                    Pad.Down -> ui.choice >= choicesOf(ui).lastIndex
                    else -> false
                }
        if (edge) scrollSheet(pad == Pad.Down)
        return edge
    }

    private fun panel(pad: Pad) {
        if (sheetEdge(vm.ui.value, pad)) return
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

/**
 * The list as it is drawn while the builder shows over it: without its focus. The list stays composed under the
 * builder, and its focus (the row the builder was opened from) is a second ring beside the builder's own, which shows
 * past the builder's edges. The focus itself is kept in [ChannelsUi] for when the builder goes.
 */
internal fun ChannelsUi.beneathBuilder(): ChannelsUi = if (builder != null) copy(focus = null) else this
