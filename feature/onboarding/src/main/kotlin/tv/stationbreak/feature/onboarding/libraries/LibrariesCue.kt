// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.libraries

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
 * True for the first [LIBRARIES_CHEER_MS] after [phase] is first [LibrariesPhase.Choosing], then false. That start is
 * saved with the screen, so coming back to the picker (Continue on "Signed in as") or a restore does not cheer again.
 */
@Composable
internal fun rememberJustArrived(phase: LibrariesPhase): Boolean {
    var saved by rememberSaveable { mutableStateOf<Long?>(null) }
    // The first frame of the list already counts, so it never shows the plain line for a frame before the cheer.
    val since = saved ?: SystemClock.elapsedRealtime().takeIf { phase == LibrariesPhase.Choosing }
    SideEffect { if (saved == null) saved = since }
    var cheering by remember(since) { mutableStateOf(cheeringAt(since, SystemClock.elapsedRealtime())) }
    LaunchedEffect(since) {
        if (since == null) return@LaunchedEffect
        delay(LIBRARIES_CHEER_MS - (SystemClock.elapsedRealtime() - since))
        cheering = false
    }
    return cheering
}
