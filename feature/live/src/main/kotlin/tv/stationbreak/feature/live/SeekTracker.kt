// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlin.math.abs

/**
 * Which `SeekDone` is the latest seek's, and whether the visit's watched time is still stopped for a seek (spec 20,
 * ADR-0021 8 and 9). The player sends one `SeekDone` per `Seeking`, and folds a seek sent while one is pending into it
 * (no second `Seeking`; the one `SeekDone` carries the final position). So a `SeekDone` is the latest seek's when that
 * seek's own `Seeking` came before it, or, with none seen, when it lands within [slackMs] of the target. Any other is
 * an earlier seek's, still buffered when the latest went out, and is dropped.
 *
 * The seek is open (the visit told `seeking`, not yet `seeked`) until its `SeekDone`, or until it is closed without one
 * ([close], [closeOnPlaying]), so the watched time never stays stopped. Pure; not thread-safe.
 */
internal class SeekTracker(
    private val slackMs: Long,
) {
    /**
     * The latest seek's `SeekDone`: [atMs] is where it landed, else where it was sent; [positionMs] the player's own
     * position (null when it could not say). [report] when the seek was still open; [live] when it is the way back to
     * live.
     */
    data class Landing(
        val atMs: Long,
        val positionMs: Long?,
        val report: Boolean,
        val live: Boolean,
    )

    private var toMs: Long? = null
    private var started = false
    private var seq = 0L
    private var liveSeq: Long? = null
    private var open = false

    /** True while the latest seek's `SeekDone` has not come. */
    val inFlight: Boolean get() = toMs != null

    /** A seek to [toMs] was sent ([live]: the way back to live); it is open from now. */
    fun sent(
        toMs: Long,
        live: Boolean,
    ) {
        seq++
        this.toMs = toMs
        started = false
        open = true
        if (live) liveSeq = seq
    }

    /** The player's `Seeking` towards [toMs]. */
    fun onSeeking(toMs: Long) {
        if (toMs == this.toMs) started = true
    }

    /** The player's `SeekDone` at [positionMs] (null: not known); the landing when it is the latest seek's. */
    fun onSeekDone(positionMs: Long?): Landing? {
        val to = toMs
        // A SeekDone with no position can only be matched by its Seeking: without one, an earlier seek's is as likely.
        val mine = to != null && (started || (positionMs != null && abs(positionMs - to) <= slackMs))
        if (!mine || to == null) return null
        val landing = Landing(positionMs ?: to, positionMs, open, seq == liveSeq)
        toMs = null
        started = false
        open = false
        if (landing.live) liveSeq = null
        return landing
    }

    /** The way back to live was given up (a pause or a step): its seek, when done, is an ordinary one. */
    fun cancelLive() {
        liveSeq = null
    }

    /** Closes an open seek at [atMs] (given back, to report as its landing); null when none is open. */
    fun close(atMs: Long): Long? {
        if (!open) return null
        open = false
        return atMs
    }

    /**
     * The play plays on at [atMs]: a seek's `SeekDone` comes before its `Playing`, so an open seek whose own `Seeking`
     * was seen has lost its `SeekDone`, and is closed. One not yet begun is left open: the `Playing` is from before it.
     */
    fun closeOnPlaying(atMs: Long): Long? = if (started) close(atMs) else null
}
