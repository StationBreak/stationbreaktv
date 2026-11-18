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
import tv.stationbreak.core.ui.RemoteKey
import tv.stationbreak.core.ui.remoteKeyOf
import tv.stationbreak.core.ui.remoteKeyToAct
import tv.stationbreak.feature.settings.LibrariesSettingsViewModel

/**
 * Settings, Libraries (spec 26): tick or untick the libraries this TV uses. Drawn by its host over live TV's picture,
 * which it leaves clear at [pictureHole] (screen dp). The host owns whether it shows; each showing is a clean visit
 * that asks the server for its libraries once.
 *
 * Keys: Up and Down move between the rows, Select ticks or unticks the focused one (applied at once), Back finishes
 * the screen: [onDone] is called once, and the host closes it. The last row signs out (#336): a first Select arms it,
 * a second signs out, and a move or Back disarms it; once the sign-out has run, [onSignedOut] is called and the host
 * leaves for the server screen, where a server can be chosen again.
 */
@Composable
fun LibrariesDestination(
    pictureHole: DpRect,
    onDone: () -> Unit,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: LibrariesSettingsViewModel = hiltViewModel()
    LibrariesRoot(viewModel, pictureHole, onDone, onSignedOut, modifier)
}

/** [LibrariesDestination] without Hilt, so a test can bring its own ViewModel. */
@Composable
internal fun LibrariesRoot(
    viewModel: LibrariesSettingsViewModel,
    pictureHole: DpRect,
    onDone: () -> Unit,
    onSignedOut: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val signedOut by rememberUpdatedState(onSignedOut)
    LaunchedEffect(viewModel) { viewModel.signedOut.collect { signedOut() } }
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

// The D-pad, Select and Back: the screen keeps all of them, so the focus never leaves it. The media keys are not
// its own.
internal fun isScreenKey(key: Key): Boolean = remoteKeyOf(key) != null

// The list scrolls up and down only.
private val LIST_PADS = setOf(RemoteKey.Up, RemoteKey.Down)

/**
 * The pad to act on for a key event, or null when the event is only kept: the shared remote policy (#823) with Up and
 * Down as the pads. Left and Right are kept but do nothing.
 */
internal fun padToAct(
    key: Key,
    type: KeyEventType,
    repeatCount: Int,
): Pad? =
    when (remoteKeyToAct(key, type, repeatCount, LIST_PADS)) {
        RemoteKey.Up -> Pad.Up
        RemoteKey.Down -> Pad.Down
        RemoteKey.Select -> Pad.Select
        RemoteKey.Back -> Pad.Back
        RemoteKey.Left, RemoteKey.Right, null -> null
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
                // Back first disarms an armed sign-out; only an unarmed Back finishes the screen.
                if (vm.disarm()) return
                finished = true
                onDone()
            }
        }
    }
}
