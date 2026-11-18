// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.libraries

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import tv.stationbreak.core.ui.stan.StanMood
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.StanCue

/** How long the picker cheers "YOU'RE IN!" after it opens, before Stan asks what to show (spec 2026-11-18). */
internal const val LIBRARIES_CHEER_MS = 2_000L

/**
 * Stan's cue on the library picker for [phase] (spec 2026-11-18, mapping table). Sign-in goes straight here, so the
 * picker carries its cheer: [justArrived] is true for its first [LIBRARIES_CHEER_MS] ([rememberJustArrived]).
 */
internal fun librariesCue(
    phase: LibrariesPhase,
    justArrived: Boolean,
): StanCue =
    when (phase) {
        LibrariesPhase.Choosing -> {
            if (justArrived) {
                StanCue(StanMood.Cheer, R.string.stan_libraries_in)
            } else {
                StanCue(StanMood.Idle, R.string.stan_libraries_choosing)
            }
        }

        LibrariesPhase.Loading, LibrariesPhase.Saving -> {
            StanCue(StanMood.Busy, R.string.stan_libraries_busy)
        }

        is LibrariesPhase.LoadFailed, LibrariesPhase.NoLibraries -> {
            StanCue(StanMood.Shrug, R.string.stan_libraries_empty)
        }

        // The list is still on screen, so "nothing on the shelf" would be untrue: a shrug, and the screen says why.
        LibrariesPhase.SaveFailed -> {
            StanCue(StanMood.Shrug, null)
        }
    }

/**
 * Whether the picker still cheers at [now]: for [LIBRARIES_CHEER_MS] from [choosingSince], when its list first showed.
 * Null (still loading, or nothing to choose from) has not started the clock, so a slow load keeps the whole moment.
 */
internal fun cheeringAt(
    choosingSince: Long?,
    now: Long,
): Boolean = choosingSince != null && now - choosingSince < LIBRARIES_CHEER_MS

/**
 * When the cheer's clock starts: once the screen is [shown] (its navigation entry resumed, so the fade-in is over),
 * at the next frame, read from [now]. Needs a frame clock in the context, as a composition's effects have.
 */
internal suspend fun cheerStart(
    shown: suspend () -> Unit,
    now: () -> Long,
): Long {
    shown()
    return withFrameNanos { now() }
}

/**
 * True for the first [LIBRARIES_CHEER_MS] the list is on screen, then false. The clock starts at the first frame
 * after the list first shows and the screen is resumed ([cheerStart]), not at its first composition: on a slow TV that
 * composition, Stan's first paint and the 700 ms fade-in used up the whole cheer unseen. Until then it is true (only
 * Choosing reads it), so the list opens on the cheer. The start is saved with the screen, so coming back to the picker
 * (Continue on "Signed in as") or a restore does not cheer again.
 */
@Composable
internal fun rememberJustArrived(phase: LibrariesPhase): Boolean {
    var since by rememberSaveable { mutableStateOf<Long?>(null) }
    val choosing = phase == LibrariesPhase.Choosing
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(choosing) {
        if (choosing && since == null) {
            since =
                cheerStart(
                    shown = { lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) } },
                    now = SystemClock::elapsedRealtime,
                )
        }
    }
    val started = since
    var cheering by remember(started) {
        mutableStateOf(started == null || cheeringAt(started, SystemClock.elapsedRealtime()))
    }
    LaunchedEffect(started) {
        if (started == null) return@LaunchedEffect
        delay(LIBRARIES_CHEER_MS - (SystemClock.elapsedRealtime() - started))
        cheering = false
    }
    return cheering
}
