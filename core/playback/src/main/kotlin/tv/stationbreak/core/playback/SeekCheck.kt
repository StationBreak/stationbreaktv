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

/** Did a start or a seek land where it was asked to? A diagnostic: playback is never changed by it. */
object SeekCheck {
    val TOLERANCE: Duration = 2.seconds

    fun check(
        requested: Duration,
        actual: Duration,
    ): SeekVerdict {
        val error = (actual - requested).absoluteValue
        return if (error <= TOLERANCE) SeekVerdict.Landed else SeekVerdict.Missed(error)
    }
}
