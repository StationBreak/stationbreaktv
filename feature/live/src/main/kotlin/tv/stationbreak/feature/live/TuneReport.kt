// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

/** Which way a tune was made, for [TuneReport.Tune]. */
enum class TuneKind(
    val label: String,
) {
    /** A cut at a block boundary to a handle resolved ahead. */
    Prepared("prepared"),

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
    ) : TuneReport {
        override fun line() =
            "tune kind=${kind.label} resolveMs=$resolveMs firstFrameMs=${firstFrameMs ?: NOT_SEPARATE} " +
                "playingMs=$playingMs posErrMs=${positionErrorMs ?: NOT_SEPARATE} fallback=${fallback ?: "none"} " +
                "lagMs=$lagMs"
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

    companion object {
        /** Logged for a time that is not known, or not separate from another. */
        const val NOT_SEPARATE = -1L
    }
}
