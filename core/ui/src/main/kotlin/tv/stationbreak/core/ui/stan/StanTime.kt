// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.stan

/** The still frame shown when motion is reduced: the motion study draws t = 3 in the same case. */
internal const val STILL_FRAME_SECONDS = 3.0

private const val NANOS_PER_SECOND = 1_000_000_000.0

/**
 * The time to draw. With the TV's "Remove animations" on, always [STILL_FRAME_SECONDS], whatever else is asked for;
 * otherwise the caller's [explicit] time, or the engine's own clock ([clockNanos] since the stage started).
 */
internal fun stanTimeSeconds(
    explicit: Float?,
    reducedMotion: Boolean,
    clockNanos: Long,
): Double =
    when {
        reducedMotion -> STILL_FRAME_SECONDS
        explicit != null -> explicit.toDouble()
        else -> clockNanos / NANOS_PER_SECOND
    }
