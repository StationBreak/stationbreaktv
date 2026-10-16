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

    /**
     * The `chronological` order of [pool]: ascending by the six-part key (see [chronologicalComparator]). The key is a
     * total order, because its last part is the canonical ID.
     */
    fun chronological(pool: List<ScheduleItem>): List<ScheduleItem> = pool.sortedWith(chronologicalComparator(withGroup = true))

    /**
     * The `chronological` key as a comparator, compared from left to right:
     * 1. the undated flag (a dated item first);
     * 2. the date, as text; an undated item's date part is equal to every other undated item's;
     * 3. the group ID, `seriesId ?: id` (left out when [withGroup] is false, which `randomInOrder` uses inside a group);
     * 4. the season number, a missing one after every present one; two missing seasons tie;
     * 5. the episode number, the same way;
     * 6. the canonical ID bytes.
     */
    internal fun chronologicalComparator(withGroup: Boolean): Comparator<ScheduleItem> =
        Comparator { a, b ->
            val dateA = dateOf(a)
            val dateB = dateOf(b)
            when {
                (dateA == null) != (dateB == null) -> if (dateA == null) 1 else -1
                dateA != null && dateB != null && dateA != dateB -> dateA.compareTo(dateB)
                withGroup && a.groupId != b.groupId -> a.groupId.value.compareTo(b.groupId.value)
                a.seasonNumber != b.seasonNumber -> compareMissingLast(a.seasonNumber, b.seasonNumber)
                a.episodeNumber != b.episodeNumber -> compareMissingLast(a.episodeNumber, b.episodeNumber)
                else -> a.id.value.compareTo(b.id.value)
            }
        }

    /**
     * The date part of the chronological key: the premiere date (a UTC calendar date `YYYY-MM-DD`), else the production
     * year zero-padded to four digits as `YYYY-01-01`, else null (undated).
     */
    internal fun dateOf(item: ScheduleItem): String? =
        item.premiereDate ?: item.productionYear?.let { "${it.toString().padStart(YEAR_DIGITS, '0')}-01-01" }

    private fun compareMissingLast(
        a: Int?,
        b: Int?,
    ): Int =
        when {
            a == null -> 1
            b == null -> -1
            else -> a.compareTo(b)
        }

    private const val YEAR_DIGITS = 4
}
