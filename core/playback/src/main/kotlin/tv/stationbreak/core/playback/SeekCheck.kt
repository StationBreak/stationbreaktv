// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface SeekVerdict {
    data object Landed : SeekVerdict

    /** The player is [error] away from where it was asked to be (always positive). */
    data class Missed(
        val error: Duration,
    ) : SeekVerdict
}

/**
 * Did a start or a seek land where it was asked to? It only judges: [TuneController] acts on a miss by falling back
 * once to a transcode that starts at the requested time (ADR-0016, 4), and judges that retry with the same tolerance.
 */
object SeekCheck {
    /** The default tolerance: 2 s either way. [TuneController] takes its own, so the app can configure it. */
    val TOLERANCE: Duration = 2.seconds

    fun check(
        requested: Duration,
        actual: Duration,
        tolerance: Duration = TOLERANCE,
    ): SeekVerdict {
        val error = (actual - requested).absoluteValue
        return if (error <= tolerance) SeekVerdict.Landed else SeekVerdict.Missed(error)
    }
}
