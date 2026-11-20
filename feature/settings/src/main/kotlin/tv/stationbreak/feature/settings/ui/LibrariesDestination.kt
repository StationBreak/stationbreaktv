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
import androidx.compose.ui.input.key.onKeyEvent
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
 * the screen: [onDone] is called once, and the host closes it. Signing out is the guide's Settings sheet's own row
 * (#336), not this screen's.
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
                .onKeyEvent { listScreenKey(it, actions::pad) }
                .focusable(),
    )
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
