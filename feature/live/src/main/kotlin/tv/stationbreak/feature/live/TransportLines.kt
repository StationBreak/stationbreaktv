// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

/**
 * A behind play's `SBLIVE seek` and `pause` lines (spec 20): each waits for the player's answer, and one that never
 * gets it is told as lost (-1). Numbers and labels only. Confined, as its [BehindPlay] is, to the sitting's dispatcher.
 */
internal class TransportLines(
    private val host: BehindHost,
) {
    /** The seek whose line is waiting for its first `SeekDone` or `Playing`. */
    private var seekLine: SeekLine? = null

    /** The clock time (epoch ms) the pause began while paused; null otherwise. */
    private var pausedAtMs: Long? = null

    /** A resume asked for, whose line waits for the next `Playing`: how long it was held, and when it was asked for. */
    private var resumeLine: ResumeLine? = null

    /** The viewer paused now: a resume still waiting for its `Playing` is over. */
    fun paused() {
        flushResume()
        pausedAtMs = host.nowMs()
    }

    /** The resume is asked for: the pause's held time is told, and its line waits for the next `Playing`. */
    fun resumed() {
        val since = pausedAtMs ?: return
        pausedAtMs = null
        val now = host.nowMs()
        flushResume()
        resumeLine = ResumeLine(now - since, now)
    }

    /** A resume still waiting for its `Playing` is told with `resumeMs=-1`: no picture was attributed to it. */
    fun flushResume() {
        resumeLine?.let { host.report(TuneReport.Pause(it.heldMs, -1)) }
        resumeLine = null
    }

    /** A seek went out; a seek folded into it gets the one answer, so an earlier one still waiting is lost (-1). */
    fun seekSent(
        direction: String,
        toMs: Long,
        route: String?,
    ) {
        seekLost()
        seekLine = SeekLine(direction, toMs, host.nowMs(), route)
    }

    /** A seek line still waiting is lost with its play (-1). */
    fun seekLost() {
        seekLine?.let { host.report(it.lost()) }
        seekLine = null
    }

    /** The latest seek's `SeekDone`: its line goes out with the time it took. */
    fun seekDone() {
        val line = seekLine ?: return
        seekLine = null
        host.report(line.done(host.nowMs()))
    }

    /** A `Playing`: it ends the wait of a seek line (its `SeekDone` never came) and of the resume line. */
    fun playing() {
        seekDone()
        val resume = resumeLine ?: return
        resumeLine = null
        host.report(TuneReport.Pause(resume.heldMs, host.nowMs() - resume.atMs))
    }

    /** The behind play ends: a seek or resume still waiting is told as lost (-1), a pause still held with its time. */
    fun unfinished() {
        seekLost()
        flushResume()
        pausedAtMs?.let { host.report(TuneReport.Pause(host.nowMs() - it, -1)) }
        pausedAtMs = null
    }
}

/**
 * A seek sent, whose line is told once the player answers. [TuneReport.Seek.toPlayingMs] is -1 when no answer was
 * attributed to it: the play ended or was retried first, or a later seek was folded into it by the player.
 */
private class SeekLine(
    val direction: String,
    val targetMs: Long,
    val sentAtMs: Long,
    val route: String?,
) {
    fun done(nowMs: Long) = TuneReport.Seek(direction, targetMs, nowMs - sentAtMs, route)

    fun lost() = TuneReport.Seek(direction, targetMs, -1, route)
}

/** A resume asked for at [atMs] after a pause held [heldMs]. */
private class ResumeLine(
    val heldMs: Long,
    val atMs: Long,
)
