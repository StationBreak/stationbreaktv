// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ordering.fnv1a64
import tv.stationbreak.core.model.ordering.splitmix64
import tv.stationbreak.core.model.schedule.ScheduleItem

/**
 * The order a channel plays its pool in (docs/rules-format.md, "Ordering"; spec 08, Unit 2). Every order comes from hash
 * keys and a total sort, so no step uses a random-number stream or a bounded integer, and the result does not depend on
 * the order the pool is given in.
 *
 * Canonical IDs are 32 lower-case ASCII hex characters, so comparing them as strings is comparing their bytes.
 */
object Orderings {
    /** The `shuffle` key of [id]: `splitmix64(seedN + fnv1a64(idBytes))`, wrapping modulo 2^64. */
    fun shuffleKey(
        seedN: ULong,
        id: CanonicalId,
    ): ULong = splitmix64(seedN + fnv1a64(id))

    /**
     * The `shuffle` order of [pool] for the epoch seed [seedN]: ascending by [shuffleKey], compared as unsigned 64-bit
     * numbers, with a tie going to the smaller canonical ID bytes. A tie needs two IDs with the same `fnv1a64`, because
     * `splitmix64` is a bijection.
     *
     * Removing an item leaves the others in the same relative order, because a key depends only on the seed and the item.
     */
    fun shuffle(
        pool: List<ScheduleItem>,
        seedN: ULong,
    ): List<ScheduleItem> {
        val keyed = pool.map { shuffleKey(seedN, it.id) to it }
        return keyed
            .sortedWith(compareBy<Pair<ULong, ScheduleItem>> { it.first }.thenBy { it.second.id.value })
            .map { it.second }
    }
}
