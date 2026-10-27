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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.feature.live.LiveCommand
import tv.stationbreak.feature.live.LiveInfo
import tv.stationbreak.feature.live.LiveState

/**
 * The program guide as live TV draws it: the channel playing, when it opened, the hole live laid its picture in (screen
 * dp; null while the picture is full screen) and what to call to tune a channel.
 */
typealias GuideSlot = @Composable (
    current: ChannelId?,
    openedAtNanos: Long,
    pictureHole: DpRect?,
    onTune: (ChannelId) -> Unit,
) -> Unit

/**
 * Where live TV lays its picture while the guide is open. Until the corner picture comes (spec 17, Task 10) it stays
 * full screen, so the guide gets no hole.
 */
internal val GuidePictureHole: DpRect? = null

/**
 * The live channel screen (spec 14, F1.3). It owns the player for each STARTED period of its lifecycle, not the
 * ViewModel: when the screen stops (Home, standby) everything is closed and released at once, and when it starts
 * again a new player and a new sitting begin, anchored to the clock. [onSignedOut] is called when the session ended.
 *
 * Live TV owns the surface and the keys; the program guide (spec 17) is a slot, [guide], drawn over the picture while
 * it is open. It gets the channel playing when it opened, when that was (`System.nanoTime`), and what to call when the
 * viewer picks a channel. The guide is a module of its own, which a feature module may not depend on, so `app` passes
 * it in.
 */
@Composable
fun LiveDestination(
    onSignedOut: () -> Unit,
    guide: GuideSlot,
) {
    val viewModel: LiveViewModel = hiltViewModel()
    LiveRoot(viewModel, onSignedOut, guide = guide)
}

/** [LiveDestination] without Hilt, so a test can bring its own ViewModel and tuner. */
@Composable
internal fun LiveRoot(
    viewModel: LiveViewModel,
    onSignedOut: () -> Unit,
    onCommand: (LiveCommand) -> Unit = viewModel::send,
    guide: GuideSlot = { _, _, _, _ -> },
    tuners: TunerFactory? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val guideOpen by viewModel.guideOpen.collectAsStateWithLifecycle()
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
    CloseGuideOnPause(owner, viewModel::closeGuide)
    LaunchedEffect(state is LiveState.SignedOut) { if (state is LiveState.SignedOut) onSignedOut() }
    LiveKeysRoot(
        state = state,
        info = info,
        onCommand = onCommand,
        guideOpen = guideOpen,
        upNow = viewModel::overlayUpNow,
        onGuide = { open -> if (open) viewModel.openGuide(System.nanoTime()) else viewModel.closeGuide() },
        // Until Select-to-tune comes (spec 17, Task 10), picking the channel on air only closes the guide.
        guide = {
            guide(viewModel.guideChannel, viewModel.guideOpenedAtNanos, GuidePictureHole) { viewModel.closeGuide() }
        },
    ) { AndroidView(factory = { surface }) }
}

/**
 * The screen with the remote's keys: [LiveScreen] under the root that takes the keys first. Split from [LiveRoot] so a
 * test can drive the same key path with a state of its own.
 *
 * Each key is judged by [upNow], read as the key arrives, when there is one: the composition's view of the overlays can
 * be a frame behind, and a Back in that frame must not open the guide twice or miss that it is open. [onGuide] opens
 * (true) or closes (false) the guide; while [guideOpen], [guide] is drawn over the picture and takes the D-pad and
 * Select, and live's own keys are off.
 */
@Suppress("LongParameterList") // The screen's state, its two key outlets and the guide's slot.
@Composable
internal fun LiveKeysRoot(
    state: LiveState,
    info: LiveInfo?,
    onCommand: (LiveCommand) -> Unit,
    guideOpen: Boolean = false,
    upNow: (() -> OverlayUp)? = null,
    onGuide: (open: Boolean) -> Unit = {},
    guide: @Composable () -> Unit = {},
    video: @Composable () -> Unit,
) {
    val owner = LocalLifecycleOwner.current
    // The root holds focus so the remote's keys reach it; its preview handler sees them before anything below does.
    val focus = remember { FocusRequester() }
    // Asked again at every start: coming back from Home must not leave the keys without a target.
    LaunchedEffect(focus, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { focus.requestFocus() }
    }
    val held = remember { HeldKeys() }
    val composed = overlayUpOf(state, info, guideOpen)
    // The actions banner's buttons or the guide held the focus; when they go the root takes it back, or no key would
    // arrive.
    LaunchedEffect(focus, composed.actions, guideOpen) { if (!composed.actions && !guideOpen) focus.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                val up = upNow?.invoke() ?: composed
                handleLiveKey(event, up, held, onGuide, onCommand).also { logLiveKey(event, it, state, up) }
            }.focusable(),
    ) {
        LiveScreen(state, info, onCommand, guide.takeIf { guideOpen }, video)
    }
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

/**
 * The guide closes as soon as live TV leaves the foreground (Home, another app), not only when it stops: Fire OS can
 * stop the screen seconds after it was left, and a viewer back within them would find the guide as they left it, focus
 * and all, rather than the picture. A guide wanted after that is opened again, afresh.
 */
@Composable
private fun CloseGuideOnPause(
    owner: LifecycleOwner,
    close: () -> Unit,
) {
    val closing by rememberUpdatedState(close)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) closing() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
