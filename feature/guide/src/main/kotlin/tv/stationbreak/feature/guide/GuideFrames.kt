// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

/** Frame-time percentiles of one guide session, in nanoseconds, nearest rank. */
internal data class FramePercentiles(
    val frames: Int,
    val p50Ns: Long,
    val p90Ns: Long,
    val p99Ns: Long,
)

/** Nearest-rank percentiles of [durationsNs] (rank = ceil(p/100 * n)); all zero for none. */
internal fun percentiles(durationsNs: LongArray): FramePercentiles {
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
internal fun missedVsyncs(
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
 * One guide session's measurement (spec 17, Task 9): numbers only, never a title, a channel name or an id,
 * so [line] is safe for logcat. [openMs] is from the open request to the first grid frame and [windowMs] the first
 * schedule call; -1 when the session never got there (no grid, no call). [droppedFrames] is what the platform says it
 * dropped (the sum of each frame report's dropped count), beside the [missedVsyncs] counted from frame times.
 * [extensionsAsked] is how often scrolling or the clock asked for more of the built schedule, and [extensionsApplied]
 * how many of those answers made it onto the grid. [tickLagMaxMs] is the most a minute tick showed a moment earlier
 * than the minute it was for (0, or 60000 when the panel's clock was a minute behind; #440), counting early reads only;
 * [tickLateMaxMs] is the latest a tick read the clock after its moment, counting late reads only.
 */
internal data class GuideReport(
    val openMs: Long,
    val windowMs: Long,
    val frames: FramePercentiles,
    val missedVsyncs: Int,
    val droppedFrames: Int,
    val rows: Int,
    val durationMs: Long,
    val extensionsAsked: Int = 0,
    val extensionsApplied: Int = 0,
    val tickLagMaxMs: Long = 0,
    val tickLateMaxMs: Long = 0,
) {
    /** The log line, in the style of the tune lines (`SBLIVE`). */
    fun line() =
        "guide openMs=$openMs windowMs=$windowMs frames=${frames.frames} p50Ms=${ms(frames.p50Ns)} " +
            "p90Ms=${ms(frames.p90Ns)} p99Ms=${ms(frames.p99Ns)} missedVsyncs=$missedVsyncs " +
            "droppedFrames=$droppedFrames rows=$rows durationMs=$durationMs " +
            "extensions=$extensionsAsked extended=$extensionsApplied tickLagMaxMs=$tickLagMaxMs " +
            "tickLateMaxMs=$tickLateMaxMs"

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
