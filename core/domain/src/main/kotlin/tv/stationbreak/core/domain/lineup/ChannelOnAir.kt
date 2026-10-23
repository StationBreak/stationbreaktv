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

    /**
     * The stand-in for a scheduled block that cannot play (spec 14, ADR-0017), from [ScheduleSource.standIns]. It is
     * its own type so a caller never mistakes it for a scheduled program: the item is always known ([info] is not
     * null, since an item gone from the library is never picked) and always joins from the start of the media, at its
     * own virtual block start. [forBlockStartMs] is the start of the scheduled block it replaces.
     */
    data class StandIn(
        val itemId: CanonicalId,
        val info: ProgramInfo,
        val forBlockStartMs: Long,
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
     * The position in the media at [atMs] (programs and stand-ins): `mediaStartMs + (atMs - startMs)`, where a
     * stand-in's `mediaStartMs` is 0. Null for a slate. Every
     * tune that joins the clock starts here; one that joins from the start of a block starts at `mediaStartMs`.
     */
    fun positionAt(atMs: Long): Duration? =
        when (airing) {
            is Airing.Program -> (airing.mediaStartMs + (atMs - startMs)).milliseconds
            is Airing.StandIn -> (atMs - startMs).milliseconds
            Airing.Slate -> null
        }
}

/**
 * What is on a channel at a moment: the [channel] as the guide knows it, the block that is on [now], and the [next]
 * blocks in order (contiguous with [now], continuing into the following broadcast day when the day runs out).
 * [builtUntilMs] is how far ahead the schedule is built: the end of the broadcast day after the one that holds [now],
 * in the channel's own day length (milliseconds since 1970). A caller that runs ahead of the clock stays inside it.
 */
data class ChannelOnAir(
    val channel: ChannelAiring,
    val now: AiringBlock,
    val next: List<AiringBlock>,
    val builtUntilMs: Long,
)
