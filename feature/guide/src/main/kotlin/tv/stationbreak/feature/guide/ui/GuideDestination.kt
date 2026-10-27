// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.DpRect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.feature.guide.GuideDirection
import tv.stationbreak.feature.guide.GuideEvent
import tv.stationbreak.feature.guide.GuideState
import tv.stationbreak.feature.guide.GuideViewModel

/**
 * The program guide (spec 17), drawn by live TV over its picture while the guide is open: live owns whether it shows,
 * this owns what it shows. Each time it appears a guide session opens with the focus on [current]'s program on air
 * now, and it closes when the guide goes. [onTune] is called when the viewer picks the program on air on a channel;
 * live TV closes the guide then.
 *
 * [pictureHole] is where live TV laid its picture while the guide is open, in screen dp: the guide leaves that
 * rectangle clear and lays its panel and grid out around it. [openedAtNanos] is when the key that opened the guide
 * arrived (`System.nanoTime`), for the guide's open-time measurement.
 *
 * Keys: the guide moves its own focus (model-driven, not Compose focus search), with the D-pad and Select; Back closes
 * the info overlay when one is open and is otherwise left to the activity, which leaves live TV.
 */
@Composable
fun GuideDestination(
    current: ChannelId?,
    openedAtNanos: Long,
    pictureHole: DpRect,
    onTune: (ChannelId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: GuideViewModel = hiltViewModel()
    GuideRoot(viewModel, current, pictureHole, onTune, modifier, openedAtNanos, viewModel.measure)
}

/** [GuideDestination] without Hilt, so a test can bring its own ViewModel. */
@Suppress("LongParameterList") // The destination's own five, and two a test leaves at their defaults.
@Composable
internal fun GuideRoot(
    viewModel: GuideViewModel,
    current: ChannelId?,
    pictureHole: DpRect,
    onTune: (ChannelId) -> Unit,
    modifier: Modifier = Modifier,
    openedAtNanos: Long = System.nanoTime(),
    measure: Boolean = false,
) {
    // The session's measurement line (Task 9) is written when the session ends, and only when [measure] says this build
    // writes it. The rows and the ticks' lag are read as they stand then.
    val view = LocalView.current
    // Started with the session, not in `remember`: a composition that is abandoned before it applies must not leak the
    // frame thread and its listener.
    var measurement by remember(viewModel) { mutableStateOf<GuideMeasurement?>(null) }
    // One session per showing, and per opening: live TV can close and reopen the guide between two frames, which leaves
    // this on screen with a new [openedAtNanos]. The guide's own state starts Closed and may say so for a moment while
    // the open runs: it never closes the guide, which is live TV's to do.
    DisposableEffect(viewModel, openedAtNanos) {
        val started = GuideMeasurement.start(measure, view, openedAtNanos)
        measurement = started
        viewModel.open(current)
        onDispose {
            try {
                val rows = (viewModel.snapshot.value.state as? GuideState.Ready)?.model?.rows?.size ?: 0
                started?.finish(
                    viewModel.windowMs,
                    rows,
                    viewModel.extensionsAsked,
                    viewModel.extensionsApplied,
                    viewModel.tickLagMaxMs,
                )
            } finally {
                viewModel.close()
            }
        }
    }
    // The first grid frame: openMs lands on the frame that drew the grid or one frame later (about 16 ms).
    LaunchedEffect(viewModel, openedAtNanos) {
        viewModel.snapshot.first { it.state is GuideState.Ready }
        withFrameNanos { }
        withFrameNanos { }
        measurement?.gridShown()
    }
    // A Tune is acted on as it is received, never right after Select: the guide may refuse a Select (info open, focus
    // gone), and a session that ends drops its queued events.
    val tune by rememberUpdatedState(onTune)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is GuideEvent.Tune -> tune(event.channel)
            }
        }
    }
    val info by viewModel.info.collectAsStateWithLifecycle()
    // Back over the info overlay closes it; with none open, Back is the activity's and leaves live TV.
    BackHandler(enabled = info != null) { viewModel.closeInfo() }
    val keys = remember { FocusRequester() }
    LaunchedEffect(keys) { keys.requestFocus() }
    val backs = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    // A move, inside its trace section ("guide move"): the step and its publish.
    val move = remember(viewModel) { { direction: GuideDirection -> traced(TRACE_MOVE) { viewModel.move(direction) } } }
    GuideScreen(
        snapshot = viewModel.snapshot,
        panel = viewModel.panel,
        info = info,
        current = current,
        pictureHole = pictureHole,
        modifier =
            modifier
                .focusRequester(keys)
                .onKeyEvent { guideKey(it, move, viewModel::select, backs?.let { d -> d::onBackPressed }) }
                .focusable(),
    )
}

/**
 * The guide's keys, all consumed so the focus never leaves the grid: the D-pad moves the focus (auto-repeat included,
 * so holding a direction scrolls) and Select acts on the focused cell once per press. Their releases are consumed
 * too.
 *
 * Back is handed to the activity's [back] dispatcher on its release, as the activity itself would: the info overlay's
 * BackHandler closes it, and with none open the activity leaves live TV. Left unconsumed, Compose would spend it on
 * moving the focus out of the guide to live TV's root ("exit"), and the viewer would have to press Back twice. The
 * media keys are not the guide's.
 */
internal fun guideKey(
    event: KeyEvent,
    move: (GuideDirection) -> Unit,
    select: () -> Unit,
    back: (() -> Unit)? = null,
): Boolean {
    val down = event.type == KeyEventType.KeyDown
    val direction = directionOf(event.key)
    val handled =
        when {
            event.key == Key.Back -> back != null
            direction != null -> true
            else -> event.key in SELECT_KEYS
        }
    when {
        !handled -> Unit

        // Any release, even one whose down the guide never saw. The Back that opened the guide never reaches here
        // (live's root holds that press to its release), so a release here is always a later press. A fast double
        // Back is such a press: its down can arrive in the frame before the guide has the focus, going to live's root,
        // which passes it, and only the release lands here. Waiting for a down the guide saw would swallow that press.
        event.key == Key.Back -> if (event.type == KeyEventType.KeyUp) back?.invoke()

        direction != null -> if (down) move(direction)

        down && event.nativeKeyEvent.repeatCount == 0 -> select()
    }
    return handled
}

private val SELECT_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

private fun directionOf(key: Key): GuideDirection? =
    when (key) {
        Key.DirectionUp -> GuideDirection.Up
        Key.DirectionDown -> GuideDirection.Down
        Key.DirectionLeft -> GuideDirection.Left
        Key.DirectionRight -> GuideDirection.Right
        else -> null
    }
