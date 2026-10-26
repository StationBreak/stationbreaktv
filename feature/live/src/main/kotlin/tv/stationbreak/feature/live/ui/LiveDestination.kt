// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.util.Log
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
    val info by viewModel.info.collectAsStateWithLifecycle()
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
    // Asked again at every start: coming back from Home must not leave the keys without a target.
    LaunchedEffect(focus, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { focus.requestFocus() }
    }
    val up = OverlayUp(actions = (state as? LiveState.Watching)?.banner?.actions == true, info = info != null)
    // The actions banner's buttons held the focus; when it goes the root takes it back, or no key would arrive.
    LaunchedEffect(focus, up.actions) { if (!up.actions) focus.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                handleLiveKey(event, up, onCommand).also { logLiveKey(event, it, state, up) }
            }.focusable(),
    ) {
        LiveScreen(state, info, onCommand) { AndroidView(factory = { surface }) }
    }
}

/** Which overlay is up, as the screen last saw it: the keys mean something else over it (Select, Back). */
internal data class OverlayUp(
    /** The actions banner (Info, Last) shows: Select belongs to its buttons. */
    val actions: Boolean = false,
    /** The program info overlay shows. */
    val info: Boolean = false,
) {
    companion object {
        val None = OverlayUp()
    }
}

private fun mappedCommand(
    key: Key,
    up: OverlayUp = OverlayUp.None,
): LiveCommand? =
    when (key) {
        Key.DirectionUp, Key.ChannelUp -> LiveCommand.Flip(FlipDirection.Up)

        Key.DirectionDown, Key.ChannelDown -> LiveCommand.Flip(FlipDirection.Down)

        Key.Menu -> LiveCommand.Previous

        Key.Info -> LiveCommand.ShowInfo

        // Over the actions banner Select clicks its focused button; over the info overlay it does nothing.
        Key.DirectionCenter, Key.Enter -> LiveCommand.ShowBanner.takeUnless { up.actions || up.info }

        // Back closes an overlay; with none up it is not ours (a later task opens the guide).
        Key.Back -> LiveCommand.DismissOverlay.takeIf { up.actions || up.info }

        else -> null
    }

/**
 * What a key does on the live screen (spec 15), or null when it does nothing. Key-down only, auto-repeat included, so
 * holding Up or Down keeps flipping. Up/Down and the remote's CH+/CH- flip, Menu goes to the previous channel, Select
 * brings the actions banner and Info the program's info (spec 17). Back is mapped only while [up] says an overlay is
 * open (a plain tune banner never consumes it), nor are the media next/previous keys, kept for pause and skip.
 */
internal fun liveKeyCommand(
    event: KeyEvent,
    up: OverlayUp = OverlayUp.None,
): LiveCommand? {
    if (event.type != KeyEventType.KeyDown) return null
    return mappedCommand(event.key, up)
}

/**
 * Sends the command of a mapped key and says whether the screen consumed [event]: a mapped key's down (which sends) and
 * its up (which does nothing, but nothing else should see a stray release). Everything else, Back included, passes.
 */
internal fun handleLiveKey(
    event: KeyEvent,
    up: OverlayUp = OverlayUp.None,
    send: (LiveCommand) -> Unit,
): Boolean {
    // Left and Right move the focus between the buttons, which is the viewer still at work: keep the banner up.
    val sideways = event.key == Key.DirectionLeft || event.key == Key.DirectionRight
    if (up.actions && sideways && event.type == KeyEventType.KeyDown) send(LiveCommand.ShowBanner)
    val command = mappedCommand(event.key, up) ?: return false
    if (event.type == KeyEventType.KeyDown) send(command)
    return event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
}

private const val LOG_TAG = "SBLIVE"

/**
 * Writes the key line for a mapped key's down (repeats included); numbers and labels only (#392). [state] is what the
 * composition last saw, so it may be one frame behind the machine.
 */
private fun logLiveKey(
    event: KeyEvent,
    handled: Boolean,
    state: LiveState,
    up: OverlayUp,
) {
    val native = event.nativeKeyEvent
    val line = keyLogLine(event.type, event.key, native.keyCode, native.repeatCount, handled, state, up)
    line?.let { Log.i(LOG_TAG, it) }
}

/** The key line of [key] when it is a mapped key's down, else null: ups and unmapped keys are not logged. */
@Suppress("LongParameterList") // The key event's parts, kept apart so a test needs no Android KeyEvent.
internal fun keyLogLine(
    type: KeyEventType,
    key: Key,
    code: Int,
    repeat: Int,
    handled: Boolean,
    state: LiveState,
    up: OverlayUp = OverlayUp.None,
): String? =
    if (type == KeyEventType.KeyDown && mappedCommand(key, up) != null) {
        liveKeyLine(code, "down", repeat, handled, state)
    } else {
        null
    }

/** The key line: `key code=<keycode> action=<down|up> repeat=<n> handled=<true|false> state=<LiveState class>`. */
internal fun liveKeyLine(
    code: Int,
    action: String,
    repeat: Int,
    handled: Boolean,
    state: LiveState,
): String = "key code=$code action=$action repeat=$repeat handled=$handled state=${state.javaClass.simpleName}"

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
