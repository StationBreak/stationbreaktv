// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import javax.inject.Qualifier

/**
 * Marks the `Boolean` that says whether this build writes the guide's measurement line. The app provides it from its
 * build type (debug and benchmark: true), since this library cannot tell a benchmark build from a release one.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class GuideMeasured

/** Frame-time percentiles of one guide session, in nanoseconds, nearest rank. */
data class FramePercentiles(
    val frames: Int,
    val p50Ns: Long,
    val p90Ns: Long,
    val p99Ns: Long,
)

/** Nearest-rank percentiles of [durationsNs] (rank = ceil(p/100 * n)); all zero for none. */
fun percentiles(durationsNs: LongArray): FramePercentiles {
    if (durationsNs.isEmpty()) return FramePercentiles(0, 0, 0, 0)
    val sorted = durationsNs.sortedArray()

    fun at(p: Int) = sorted[((p * sorted.size + PERCENT - 1) / PERCENT - 1).coerceIn(0, sorted.lastIndex)]
    return FramePercentiles(sorted.size, at(P50), at(P90), at(P99))
}

/**
 * Vsyncs the display went by while frames were being drawn: each frame of [frameDurationsNs] that took longer than one
 * refresh period misses `ceil(duration / period) - 1` of them. Time between frames (nothing to draw) counts for
 * nothing. A zero [periodNs] counts none.
 */
fun missedVsyncs(
    frameDurationsNs: LongArray,
    periodNs: Long,
): Int {
    if (periodNs <= 0) return 0
    var missed = 0L
    for (d in frameDurationsNs) {
        val periods = (d + periodNs - 1) / periodNs
        if (periods > 1) missed += periods - 1
    }
    return missed.toInt()
}

/**
 * One guide session's measurement (spec 17, Task 9): numbers and booleans only, never a title, a channel name or an id,
 * so [line] is safe for logcat. [openMs] is from the open request to the first grid frame and [windowMs] the first
 * schedule call; -1 when the session never got there (no grid, no call).
 */
data class GuideReport(
    val openMs: Long,
    val windowMs: Long,
    val frames: FramePercentiles,
    val missedVsyncs: Int,
    val rows: Int,
    val pip: Boolean,
    val durationMs: Long,
) {
    /** The log line, in the style of the tune lines (`SBLIVE`). */
    fun line() =
        "guide openMs=$openMs windowMs=$windowMs frames=${frames.frames} p50Ms=${ms(frames.p50Ns)} " +
            "p90Ms=${ms(frames.p90Ns)} p99Ms=${ms(frames.p99Ns)} missedVsyncs=$missedVsyncs rows=$rows pip=$pip " +
            "durationMs=$durationMs"

    // Milliseconds to one decimal, without a locale.
    private fun ms(ns: Long): String {
        val tenths = (ns + TENTH_NS / 2) / TENTH_NS
        return "${tenths / TENTHS}.${tenths % TENTHS}"
    }
}

private const val PERCENT = 100
private const val P50 = 50
private const val P90 = 90
private const val P99 = 99
private const val TENTH_NS = 100_000L
private const val TENTHS = 10L
