// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.firstsync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import tv.stationbreak.core.ui.stan.StanHostRecorder
import tv.stationbreak.core.ui.stan.StanRenderer
import tv.stationbreak.core.ui.stan.rememberStanRenderer

/** Calls the one callback this event is for. */
internal fun FirstSyncEvent.sendTo(
    onFinished: () -> Unit,
    onSessionLost: () -> Unit,
    onCheered: () -> Unit,
) = when (this) {
    FirstSyncEvent.Finished -> onFinished()
    FirstSyncEvent.Cheered -> onCheered()
    FirstSyncEvent.SessionLost -> onSessionLost()
}

/**
 * What the cheer and its still share, so the still is the cheer's own last frame: one Stan renderer (his parsed paths
 * and glyphs, made once, so the still's first frame builds nothing) and the recorder of the moment Stan last drew. The
 * first-sync destination's caller remembers one ([rememberFirstSyncCheerFrame]) and hands it to [FirstSyncDestination];
 * after the cheer it is held for [FirstSyncCheerStill] only until the still lifts, then let go.
 */
@Stable
class FirstSyncCheerFrame internal constructor(
    internal val renderer: StanRenderer,
) {
    internal val recorder = StanHostRecorder()
}

/** A [FirstSyncCheerFrame] for the caller's composition. */
@Composable
fun rememberFirstSyncCheerFrame(): FirstSyncCheerFrame {
    val renderer = rememberStanRenderer()
    return remember(renderer) { FirstSyncCheerFrame(renderer) }
}

/**
 * The cheer frozen: the screen as it was on the last frame of Stan's cheer ([cheerFrame]'s recorder), drawn with the
 * same renderer, with nothing moving. The graph draws it over live TV after [FirstSyncEvent.Cheered] while live TV
 * gets its first picture, so the first session goes from the cheer to a programme with no stand-by card and no jump in
 * between; frozen, so nothing in it can drop frames while the next screen is built underneath. It takes no key and no
 * focus, and a screen reader skips it: the cheer has already been said.
 */
@Composable
fun FirstSyncCheerStill(
    cheerFrame: FirstSyncCheerFrame,
    modifier: Modifier = Modifier,
) {
    // The moment as the cheer last drew it, read once: the still never moves on.
    val frozenAt = remember(cheerFrame) { cheerFrame.recorder.last }
    FirstSyncScreen(
        FirstSyncPhase.Done,
        onRetry = {},
        modifier = modifier.clearAndSetSemantics {},
        cheerFrame = cheerFrame,
        frozenAt = frozenAt,
    )
}
