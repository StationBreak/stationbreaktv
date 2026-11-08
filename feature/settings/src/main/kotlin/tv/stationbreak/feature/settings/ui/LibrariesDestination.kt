// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings.ui

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
import tv.stationbreak.feature.settings.LibrariesSettingsViewModel

/**
 * Settings, Libraries (spec 26): tick or untick the libraries this TV uses. Drawn by its host over live TV's picture,
 * which it leaves clear at [pictureHole] (screen dp). The host owns whether it shows; each showing is a clean visit
 * that asks the server for its libraries once.
 *
 * Keys: Up and Down move between the rows, Select ticks or unticks the focused one (applied at once), Back finishes
 * the screen: [onDone] is called once, and the host closes it.
 */
@Composable
fun LibrariesDestination(
    pictureHole: DpRect,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: LibrariesSettingsViewModel = hiltViewModel()
    LibrariesRoot(viewModel, pictureHole, onDone, modifier)
}

/** [LibrariesDestination] without Hilt, so a test can bring its own ViewModel. */
@Composable
internal fun LibrariesRoot(
    viewModel: LibrariesSettingsViewModel,
    pictureHole: DpRect,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(viewModel) {
        viewModel.open()
        onDispose { viewModel.close() }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keys = remember { FocusRequester() }
    LaunchedEffect(keys) { keys.requestFocus() }
    val done by rememberUpdatedState(onDone)
    val actions = remember(viewModel) { LibrariesActions(viewModel) { done() } }
    LibrariesSettingsScreen(
        state = state,
        pictureHole = pictureHole,
        modifier =
            modifier
                .focusRequester(keys)
                .onKeyEvent { librariesKey(it, actions::pad) }
                .focusable(),
    )
}

/** A key the screen acts on. */
internal enum class Pad { Up, Down, Select, Back }

// The D-pad and Back: the screen keeps all of them, so the focus never leaves it. The media keys are not its own.
internal fun isScreenKey(key: Key): Boolean =
    when (key) {
        Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> true
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Back -> true
        else -> false
    }

/**
 * The pad to act on for a key event, or null when the event is only kept: Back on its release (a fast double Back can
 * reach the screen as a release alone), Select on its first press (never its auto-repeat), Up and Down on every press
 * (so holding one scrolls). Left and Right do nothing.
 */
internal fun padToAct(
    key: Key,
    type: KeyEventType,
    repeatCount: Int,
): Pad? {
    val down = type == KeyEventType.KeyDown
    return when (key) {
        Key.Back -> Pad.Back.takeIf { type == KeyEventType.KeyUp }
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> Pad.Select.takeIf { down && repeatCount == 0 }
        Key.DirectionUp -> Pad.Up.takeIf { down }
        Key.DirectionDown -> Pad.Down.takeIf { down }
        else -> null
    }
}

internal fun librariesKey(
    event: KeyEvent,
    pad: (Pad) -> Unit,
): Boolean {
    if (!isScreenKey(event.key)) return false
    padToAct(event.key, event.type, event.nativeKeyEvent.repeatCount)?.let(pad)
    return true
}

/** What a key does. Back finishes the screen once: a key that follows it (a fast double Back) is ignored. */
internal class LibrariesActions(
    private val vm: LibrariesSettingsViewModel,
    private val onDone: () -> Unit,
) {
    private var finished = false

    fun pad(pad: Pad) {
        if (finished) return
        when (pad) {
            Pad.Up -> {
                vm.move(up = true)
            }

            Pad.Down -> {
                vm.move(up = false)
            }

            Pad.Select -> {
                vm.select()
            }

            Pad.Back -> {
                finished = true
                onDone()
            }
        }
    }
}
