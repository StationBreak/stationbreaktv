// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.model.CanonicalId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** What a block of a channel's schedule airs: a program of the library, or a slate (a gap with nothing to play). */
sealed interface Airing {
    /**
     * An item of the library. [mediaStartMs] is where in the media the block starts playing
     * (`ScheduleContent.Program.mediaStartOffsetMs`; ADR-0002: an item may span blocks). [info] names it, and is null
     * when the item has no current row (it is gone from the library); [itemId] is kept so the caller can still skip it.
     */
    data class Program(
        val itemId: CanonicalId,
        val mediaStartMs: Long,
        val info: ProgramInfo?,
    ) : Airing

    /** Nothing to play: the grid gap before a program, or a whole day with nothing to air. */
    data object Slate : Airing
}

/** One block of a channel's schedule, half-open: it covers `[startMs, endMs)`, in milliseconds since 1970. */
data class AiringBlock(
    val startMs: Long,
    val endMs: Long,
    val airing: Airing,
) {
    /**
     * The position in the media at [atMs] (programs only): `mediaStartMs + (atMs - startMs)`. Null for a slate. Every
     * tune that joins the clock starts here; one that joins from the start of a block starts at `mediaStartMs`.
     */
    fun positionAt(atMs: Long): Duration? =
        when (airing) {
            is Airing.Program -> (airing.mediaStartMs + (atMs - startMs)).milliseconds
            Airing.Slate -> null
        }
}

/**
 * What is on a channel at a moment: the [channel] as the guide knows it, the block that is on [now], and the [next]
 * blocks in order (contiguous with [now], continuing into the following broadcast day when the day runs out).
 */
data class ChannelOnAir(
    val channel: ChannelAiring,
    val now: AiringBlock,
    val next: List<AiringBlock>,
)
