// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ordering.fnv1a64
import tv.stationbreak.core.model.ordering.splitmix64
import tv.stationbreak.core.model.schedule.ScheduleItem

/** One programme of a [GapFill]: [item] airs from [startMs] to [endMs] (its whole runtime). */
internal data class FillPiece(
    val item: ScheduleItem,
    val startMs: Long,
    val endMs: Long,
)

/**
 * The deterministic fill for a block whose item has left the library (spec 26): programmes from the same channel's day
 * pool that fit the gap, the longest first. The same inputs give the same pieces whatever the pool's order, so the
 * guide and the live screen agree.
 */
internal object GapFill {
    /**
     * Pieces for the gap `[slotStartMs, slotEndMs)` of [channel]: repeatedly the airable, not [exclude]d, not yet used
     * item of [pool] with the largest runtime that still fits what is left (ties by
     * `splitmix64(base xor fnv1a64(id))` ascending, unsigned, then id), laid end to end from the slot's start, where
     * `base = splitmix64(fnv1a64(channel) + slotStartMs)`. It stops when nothing fits; time left over is the caller's.
     */
    @Suppress("LongParameterList") // The pinned formula's inputs, one for one.
    fun pick(
        channel: ChannelId,
        pool: List<ScheduleItem>,
        slotStartMs: Long,
        slotEndMs: Long,
        exclude: Set<CanonicalId>,
        airable: (ScheduleItem) -> Boolean,
    ): List<FillPiece> {
        val base = splitmix64(fnv1a64(channel.value.encodeToByteArray()) + slotStartMs.toULong())
        val usable =
            pool
                .filter { it.id !in exclude && airable(it) }
                .map { (splitmix64(base xor fnv1a64(it.id))) to it }
                .sortedWith(
                    compareByDescending<Pair<ULong, ScheduleItem>> { it.second.runtimeMs }
                        .thenBy { it.first }
                        .thenBy { it.second.id.value },
                )
        var remaining = slotEndMs - slotStartMs
        var cursor = slotStartMs
        val used = HashSet<CanonicalId>()
        val pieces = ArrayList<FillPiece>()
        while (true) {
            val next = usable.firstOrNull { (_, it) -> it.id !in used && it.runtimeMs <= remaining }?.second ?: break
            pieces += FillPiece(next, cursor, cursor + next.runtimeMs)
            used += next.id
            cursor += next.runtimeMs
            remaining -= next.runtimeMs
        }
        return pieces
    }
}

/**
 * What a fill never picks (spec 26): the [gone] id, every pool item with no current row (not in [live]), and the items
 * of the fix being filled around ([fixPieces]). The one rule for the naming fill and for the repair's fill.
 */
internal fun fillExclusions(
    pool: List<ScheduleItem>,
    live: Set<CanonicalId>,
    gone: CanonicalId,
    fixPieces: Set<CanonicalId>,
): Set<CanonicalId> = fixPieces + gone + pool.mapNotNull { it.id.takeIf { id -> id !in live } }
