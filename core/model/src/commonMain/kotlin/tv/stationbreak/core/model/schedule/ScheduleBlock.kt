// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.schedule

import tv.stationbreak.core.model.CanonicalId

/** What a [ScheduleBlock] holds: an item, or a station-ID slate for time nothing else fills. */
sealed interface ScheduleContent {
    /**
     * An item. [mediaStartOffsetMs] is where in the media the block starts playing. It is always 0 in version 1,
     * because no program is ever joined part-way through at the start of a block.
     */
    data class Program(
        val itemId: CanonicalId,
        val mediaStartOffsetMs: Long = 0L,
    ) : ScheduleContent {
        init {
            require(mediaStartOffsetMs >= 0L) { "A media offset is not negative" }
        }
    }

    /** A gap with no program. F3.1's filler will replace it. */
    data object Slate : ScheduleContent
}

/**
 * One entry of a day's lineup, half-open: it covers `[startMs, endMs)`, so a time exactly on an edge belongs to
 * the later block. A zero-length block is never built. Times are milliseconds since 1970-01-01T00:00:00Z.
 */
data class ScheduleBlock(
    val startMs: Long,
    val endMs: Long,
    val content: ScheduleContent,
) {
    init {
        require(endMs > startMs) { "A block is longer than zero" }
    }

    val lengthMs: Long get() = endMs - startMs

    /** True when [t] is in `[startMs, endMs)`. */
    fun contains(t: Long): Boolean = t >= startMs && t < endMs
}
