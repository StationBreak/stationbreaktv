// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.view.SurfaceView
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import tv.stationbreak.feature.live.FlipDirection
import tv.stationbreak.feature.live.LiveCommand
import tv.stationbreak.feature.live.LiveState

/**
 * The live channel screen (spec 14, F1.3). It owns the player for each STARTED period of its lifecycle, not the
 * ViewModel: when the screen stops (Home, standby) everything is closed and released at once, and when it starts
 * again a new player and a new sitting begin, anchored to the clock. [onSignedOut] is called when the session ended.
 */
@Composable
fun LiveDestination(onSignedOut: () -> Unit) {
    val viewModel: LiveViewModel = hiltViewModel()
    LiveRoot(viewModel, onSignedOut)
}

/** [LiveDestination] without Hilt, so a test can bring its own ViewModel and tuner. */
@Composable
internal fun LiveRoot(
    viewModel: LiveViewModel,
    onSignedOut: () -> Unit,
    onCommand: (LiveCommand) -> Unit = viewModel::send,
    tuners: TunerFactory? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    // One surface view for the screen's life; each period's player attaches to it and detaches on the way out.
    val surface = remember(context) { SurfaceView(context) }
    LaunchedEffect(viewModel, owner, surface) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (tuners == null) viewModel.sit(context, surface) else viewModel.sit(context, surface, tuners)
        }
    }
    KeepScreenOn(owner)
    LaunchedEffect(state is LiveState.SignedOut) { if (state is LiveState.SignedOut) onSignedOut() }
    // The root holds focus so the remote's keys reach it; its preview handler sees them before anything below does.
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onPreviewKeyEvent { event -> liveKeyCommand(event)?.also(onCommand) != null }
            .focusable(),
    ) {
        LiveScreen(state) { AndroidView(factory = { surface }) }
    }
}

/**
 * What a key does on the live screen (spec 15), or null when the screen leaves it alone. Key-down only, auto-repeat
 * included, so holding Up or Down keeps flipping; the release does nothing. Up/Down and the remote's CH+/CH- flip,
 * Menu goes to the previous channel. Back is never mapped (it still leaves the app from live), nor are the media
 * next/previous keys, which are kept for pause and skip.
 */
internal fun liveKeyCommand(event: KeyEvent): LiveCommand? {
    if (event.type != KeyEventType.KeyDown) return null
    return when (event.key) {
        Key.DirectionUp, Key.ChannelUp -> LiveCommand.Flip(FlipDirection.Up)
        Key.DirectionDown, Key.ChannelDown -> LiveCommand.Flip(FlipDirection.Down)
        Key.Menu -> LiveCommand.Previous
        else -> null
    }
}

/**
 * A TV channel never lets the device sleep while it is on screen (snow, slates and the "No channels" card included):
 * the view keeps the screen on from STARTED until the screen leaves or stops. A view flag, so the feature does not
 * touch the activity's window.
 */
@Composable
private fun KeepScreenOn(owner: LifecycleOwner) {
    val view = LocalView.current
    DisposableEffect(view, owner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) view.keepScreenOn = true
                if (event == Lifecycle.Event.ON_STOP) view.keepScreenOn = false
            }
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.keepScreenOn = true
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            view.keepScreenOn = false
        }
    }
}
