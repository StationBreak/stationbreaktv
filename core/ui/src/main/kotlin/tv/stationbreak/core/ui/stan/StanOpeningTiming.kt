// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.stan

import kotlin.math.max
import kotlin.math.min

/** How long the opening screen stays with reduced motion: the still frame, then on as soon as the app is ready. */
internal const val OPENING_REDUCED_DWELL_SECONDS = 1.0

/** How long the opening fades out over the next screen once it has handed over (none with reduced motion). */
internal const val OPENING_DISSOLVE_MILLIS = 300

internal fun openingDissolveMillis(reducedMotion: Boolean): Int = if (reducedMotion) 0 else OPENING_DISSOLVE_MILLIS

/**
 * The seconds at which a ready app leaves the opening: the moment the name is fully in ([OPENING_TITLE_IN], from the
 * scene's own title ramps), and never later than the scene's run ([StanScene.runSeconds]). With reduced motion there
 * is nothing to wait for, so the still frame's short dwell.
 */
internal fun openingDwellSeconds(reducedMotion: Boolean): Double =
    if (reducedMotion) {
        OPENING_REDUCED_DWELL_SECONDS
    } else {
        min(OPENING_TITLE_IN, StanScene.Opening.runSeconds ?: OPENING_TITLE_IN)
    }

/**
 * How many seconds are left before the opening should hand over, or null while it must keep playing.
 *
 * - **Not ready** ([appReady] false): null, even past the run's end; the scene loops until the app is ready. A key
 *   ([skipped]) does not change that: there is nowhere to go yet.
 * - **Ready:** the rest of [openingDwellSeconds] after [elapsedSeconds], 0 once it has passed (a late app leaves at
 *   once, with no wait added). A key press ([skipped]) makes it 0 straight away.
 */
internal fun openingRemainingSeconds(
    elapsedSeconds: Double,
    appReady: Boolean,
    reducedMotion: Boolean,
    skipped: Boolean,
): Double? =
    when {
        !appReady -> null
        skipped -> 0.0
        else -> max(0.0, openingDwellSeconds(reducedMotion) - elapsedSeconds)
    }
