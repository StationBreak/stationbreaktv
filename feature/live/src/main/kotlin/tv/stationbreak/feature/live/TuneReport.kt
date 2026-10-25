// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlin.time.Duration
import kotlin.time.Instant

/** Which way a tune was made, for [TuneReport.Tune]. */
enum class TuneKind(
    val label: String,
) {
    /** A cut at a block boundary to a handle resolved ahead. */
    Prepared("prepared"),

    /** A flip to a neighbour channel whose handle was resolved ahead and is played from the position at the commit. */
    Neighbour("neighbour"),

    /** A tune that resolves for itself: tuning in, a cut with no usable handle, a re-anchor. */
    Fresh("fresh"),

    /** A stand-in in place of a dead show. */
    StandIn("standin"),

    /** The second try of a show whose first try failed. */
    Retry("retry"),
}

/**
 * What the live channel measured about a tune (spec 14): numbers and fixed names only, never an id, a title, a URL or
 * a message, so [line] is safe for logcat. The device check reads these lines instead of guessing.
 */
sealed interface TuneReport {
    /** The log line, `SBLIVE` format. */
    fun line(): String

    /**
     * One tune that played. [resolveMs] is 0 for a prepared tune (resolved ahead) and [NOT_SEPARATE] for any other:
     * the controller does not split a fresh tune's resolve from its play, so it is inside [firstFrameMs] and
     * [playingMs]. A null time is logged as [NOT_SEPARATE]. [lagMs] is how far behind the schedule the picture started:
     * `(time at playing - block start) - (position tuned - media position at the block start)`.
     */
    data class Tune(
        val kind: TuneKind,
        val resolveMs: Long,
        val firstFrameMs: Long?,
        val playingMs: Long,
        val positionErrorMs: Long?,
        val fallback: String?,
        val lagMs: Long,
        val route: String? = null,
        val from: String? = null,
        val tracksMs: Long? = null,
        val skippedFrames: Long? = null,
    ) : TuneReport {
        override fun line() =
            "tune kind=${kind.label} resolveMs=$resolveMs firstFrameMs=${firstFrameMs ?: NOT_SEPARATE} " +
                "playingMs=$playingMs posErrMs=${positionErrorMs ?: NOT_SEPARATE} fallback=${fallback ?: "none"} " +
                "lagMs=$lagMs from=${from ?: "none"} route=${route ?: "none"} " +
                "tracksMs=${tracksMs ?: NOT_SEPARATE} skippedFrames=${skippedFrames ?: NOT_SEPARATE}"
    }

    /**
     * A block boundary cut: [gapMs] from the end of the old block to the new picture, with [resolveMs] as above and
     * [toFirstFrameMs] the tune's own time to its first picture.
     */
    data class Cut(
        val gapMs: Long,
        val resolveMs: Long,
        val toFirstFrameMs: Long?,
    ) : TuneReport {
        override fun line() = "cut gapMs=$gapMs resolveMs=$resolveMs toFirstFrameMs=${toFirstFrameMs ?: NOT_SEPARATE}"
    }

    /**
     * A sitting that broke on an unexpected exception and was started again. [error] is the exception's class name,
     * never its message: a message can hold an id or a path.
     */
    data class Failed(
        val error: String,
    ) : TuneReport {
        override fun line() = "sitting failed error=$error"
    }

    /**
     * One flip, timed from the viewer's last key press (spec 15): [toFirstFrameMs] and [toPlayingMs] include the
     * [settleMs] wait, so they are what the viewer waited. A null time is logged as [NOT_SEPARATE]. [elapsedMs] is
     * the time from the last press to the [outcome], whatever it is: `playing`, `slate`, `standin`, `failed`, or, for a
     * flip that never resolved, `superseded` (a newer commit replaced it) or `abandoned` (the run stopped first).
     * [route], [from] and [fallback] are the first tune's, as in [Tune].
     */
    data class Flip(
        val toFirstFrameMs: Long?,
        val toPlayingMs: Long?,
        val settleMs: Long,
        val elapsedMs: Long,
        val outcome: String,
        val route: String?,
        val fallback: String?,
        val from: String? = null,
    ) : TuneReport {
        override fun line() =
            "flip toFirstFrameMs=${toFirstFrameMs ?: NOT_SEPARATE} toPlayingMs=${toPlayingMs ?: NOT_SEPARATE} " +
                "settleMs=$settleMs elapsedMs=$elapsedMs outcome=$outcome from=${from ?: "none"} " +
                "route=${route ?: "none"} fallback=${fallback ?: "none"}"
    }

    companion object {
        /** Logged for a time that is not known, or not separate from another. */
        const val NOT_SEPARATE = -1L
    }
}

/** One record of how the tune went (numbers only), and, for the first tune after a boundary, the cut's gap. */
internal fun Sitting.reportTune(
    what: TuneWhat,
    timings: TuneTimings,
    cutSince: Instant?,
) {
    val block = what.block
    val mediaAtStart = block.positionAt(block.startMs) ?: Duration.ZERO
    val lagMs = (vnowMs() - block.startMs) - (what.position - mediaAtStart).inWholeMilliseconds
    val cached = what.kind == TuneKind.Prepared || what.kind == TuneKind.Neighbour
    val resolveMs = if (cached) 0L else TuneReport.NOT_SEPARATE
    report(
        TuneReport.Tune(
            what.kind,
            resolveMs,
            timings.firstFrameMs,
            timings.playingMs,
            timings.positionErrorMs,
            timings.fallback,
            lagMs,
            timings.route,
            timings.from,
            timings.tracksMs,
            timings.skippedFrames,
        ),
    )
    if (cutSince != null) {
        val gapMs = (clock.now() - cutSince).inWholeMilliseconds
        report(TuneReport.Cut(gapMs, resolveMs, timings.firstFrameMs))
    }
}

/** The flip's line, once its first tune has played. Times run from the viewer's last key press ([Sitting.flip]). */
internal fun Sitting.reportFlipPlayed(
    kind: TuneKind,
    began: Instant,
    returned: Instant,
    timings: TuneTimings,
) {
    val timing = flip ?: return
    flip = null
    if (!timing.claim()) return
    val waitMs = (began - timing.lastCommandAt).inWholeMilliseconds
    report(
        TuneReport.Flip(
            timings.firstFrameMs?.let { waitMs + it },
            (returned - timing.lastCommandAt).inWholeMilliseconds,
            timing.settleMs,
            (returned - timing.lastCommandAt).inWholeMilliseconds,
            if (kind == TuneKind.StandIn) "standin" else "playing",
            timings.route,
            timings.fallback,
            timings.from,
        ),
    )
}

/** The flip's line when its first block came to a card instead of a picture: [outcome] is `slate` or `failed`. */
internal fun Sitting.reportFlipEnded(outcome: String) {
    val timing = flip ?: return
    flip = null
    if (!timing.claim()) return
    report(timing.ended(outcome, clock.now()))
}

/** The line of a flip with no picture, or none yet: no times but the wait since the last press. */
internal fun FlipTiming.ended(
    outcome: String,
    now: Instant,
) = TuneReport.Flip(null, null, settleMs, (now - lastCommandAt).inWholeMilliseconds, outcome, null, null)
