// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.ordering.fnv1a64
import tv.stationbreak.core.model.ordering.splitmix64
import tv.stationbreak.core.model.schedule.Caps
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.ScheduleItem

/**
 * The order a channel plays its pool in (docs/rules-format.md, "Ordering"; spec 08, Unit 2). Every order comes from
 * hash keys and a total sort, so no step uses a random-number stream or a bounded integer, and the result does not
 * depend on the order the pool is given in.
 *
 * Canonical IDs are 32 lower-case ASCII hex characters, so comparing them as strings is comparing their bytes.
 */
@Suppress("TooManyFunctions") // The orders of rules format versions 1 and 2 share one set of keys.
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
     * Removing an item leaves the others in the same relative order, because a key depends only on the seed and the
     * item.
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
     * The `chronological` order of [pool]: ascending by [chronologicalKey]. The key is a total order, because its last
     * part is the canonical ID.
     */
    fun chronological(pool: List<ScheduleItem>): List<ScheduleItem> = pool.sortedWith(chronologicalKey)

    /**
     * The `chronological` key, compared from left to right:
     * 1. the undated flag (a dated item first);
     * 2. the date, as text; an undated item's date part is equal to every other undated item's;
     * 3. the group ID, `seriesId ?: id`;
     * 4. the season number, a missing one after every present one; two missing seasons tie;
     * 5. the episode number, the same way;
     * 6. the canonical ID bytes.
     */
    internal val chronologicalKey: Comparator<ScheduleItem> =
        Comparator { a, b ->
            val dateA = dateOf(a)
            val dateB = dateOf(b)
            when {
                (dateA == null) != (dateB == null) -> if (dateA == null) 1 else -1
                dateA != null && dateB != null && dateA != dateB -> dateA.compareTo(dateB)
                a.groupId != b.groupId -> a.groupId.value.compareTo(b.groupId.value)
                a.seasonNumber != b.seasonNumber -> compareMissingLast(a.seasonNumber, b.seasonNumber)
                a.episodeNumber != b.episodeNumber -> compareMissingLast(a.episodeNumber, b.episodeNumber)
                else -> a.id.value.compareTo(b.id.value)
            }
        }

    /**
     * The order of the items inside one `randomInOrder` group: **the numbering decides, not the air date**, compared
     * from left to right:
     * 1. the season class: a regular season (1 or more) is 0, specials (season 0) are 1, a missing season is 2, so
     *    specials play after the last season and a missing season after the specials;
     * 2. the season number;
     * 3. the episode number, a missing one last; two missing episodes tie;
     * 4. the undated flag (a dated item first);
     * 5. the date, as text (an undated item's date part is equal to every other undated item's);
     * 6. the canonical ID bytes.
     */
    internal val inGroupKey: Comparator<ScheduleItem> =
        Comparator { a, b ->
            val dateA = dateOf(a)
            val dateB = dateOf(b)
            when {
                seasonClass(a) != seasonClass(b) -> seasonClass(a).compareTo(seasonClass(b))
                a.seasonNumber != b.seasonNumber -> compareMissingLast(a.seasonNumber, b.seasonNumber)
                a.episodeNumber != b.episodeNumber -> compareMissingLast(a.episodeNumber, b.episodeNumber)
                (dateA == null) != (dateB == null) -> if (dateA == null) 1 else -1
                dateA != null && dateB != null && dateA != dateB -> dateA.compareTo(dateB)
                else -> a.id.value.compareTo(b.id.value)
            }
        }

    /**
     * The order of the films inside a collection group (Rotation): the undated flag (a dated item first), the date, as
     * text, then the canonical ID bytes.
     */
    internal val releaseKey: Comparator<ScheduleItem> =
        Comparator { a, b ->
            val dateA = dateOf(a)
            val dateB = dateOf(b)
            when {
                (dateA == null) != (dateB == null) -> if (dateA == null) 1 else -1
                dateA != null && dateB != null && dateA != dateB -> dateA.compareTo(dateB)
                else -> a.id.value.compareTo(b.id.value)
            }
        }

    private fun seasonClass(item: ScheduleItem): Int =
        when (val season = item.seasonNumber) {
            null -> SEASON_CLASS_MISSING
            0 -> SEASON_CLASS_SPECIALS
            else -> if (season >= 1) SEASON_CLASS_REGULAR else SEASON_CLASS_MISSING
        }

    /** One `randomInOrder` group: a series, or a movie (or a series-less episode) on its own. */
    class OrderGroup internal constructor(
        val groupId: CanonicalId,
        /** `splitmix64(seed + fnv1a64(groupIdBytes))`, with the channel seed. */
        val key: ULong,
        /**
         * The group's items in [inGroupKey] order, the numbering deciding; the films of a collection group (Rotation)
         * in [releaseKey] order.
         */
        val items: List<ScheduleItem>,
    )

    /**
     * The `randomInOrder` key of the group [groupId]: `splitmix64(seed + fnv1a64(groupIdBytes))`. It uses the **channel
     * seed**, not `seed_n`, so the order of the groups, and so the walk, is the same on every day.
     */
    fun groupKey(
        seed: Seed,
        groupId: CanonicalId,
    ): ULong = splitmix64(seed.value + fnv1a64(groupId))

    /**
     * The groups of [pool] in key order: ascending by [groupKey] as unsigned numbers, a tie to the smaller group ID
     * bytes. Each group's items are in [inGroupKey] order (season class, season, episode, then date and ID).
     */
    fun groups(
        pool: List<ScheduleItem>,
        seed: Seed,
    ): List<OrderGroup> = groups(pool, seed, GroupRules(FilmCollections.of(emptyMap())))

    /**
     * The groups of [pool] as [rules] defines them, in the same key order as [groups]. A series keeps [inGroupKey]; a
     * collection's films are in [releaseKey] order.
     */
    internal fun groups(
        pool: List<ScheduleItem>,
        seed: Seed,
        rules: GroupRules,
    ): List<OrderGroup> =
        pool
            .groupBy { rules.groupId(it) }
            .map { (groupId, items) ->
                val inGroup = if (rules.kind(items.first()) == GroupKind.Collection) releaseKey else inGroupKey
                OrderGroup(groupId, groupKey(seed, groupId), items.sortedWith(inGroup))
            }.sortedWith(compareBy<OrderGroup> { it.key }.thenBy { it.groupId.value })

    /**
     * The `randomInOrder` walk of one cycle, as **turns**. In each round, every group in key order takes up to
     * [blockLength] of its next items as one turn; a group with fewer left gives a short turn, and a used-up group is
     * skipped. The cycle ends when every group is used up (the walk then restarts from the beginning, which is the next
     * cycle). One turn is one run.
     */
    fun randomInOrderTurns(
        pool: List<ScheduleItem>,
        seed: Seed,
        blockLength: Int,
    ): List<List<ScheduleItem>> {
        require(blockLength >= 1) { "A run is at least one item" }
        return turns(groups(pool, seed), pool.size) { group, from -> minOf(group.items.size, from + blockLength) }
    }

    /**
     * The Rotation walk of one cycle: the `randomInOrder` walk with each group's own turn length, [GroupRules.cap] of
     * [caps], and with the films of a collection as one group. The channel seed keys the groups, so the walk is the
     * same every day.
     *
     * A turn ends before the item that would make its run longer than [epochMs]; the group's next turn continues from
     * that item, so its place is kept. A turn always holds at least one item.
     */
    internal fun rotationTurns(
        pool: List<ScheduleItem>,
        seed: Seed,
        caps: Caps,
        groups: GroupRules,
        epochMs: Long,
    ): List<List<ScheduleItem>> =
        turns(groups(pool, seed, groups), pool.size) { group, from ->
            endOfRotationTurn(group.items, from, groups.cap(group.items.first(), caps), epochMs)
        }

    // The end (exclusive) of the turn that starts at [from]: up to [cap] items that fit in [epochMs], at least one.
    private fun endOfRotationTurn(
        items: List<ScheduleItem>,
        from: Int,
        cap: Int,
        epochMs: Long,
    ): Int {
        val limit = minOf(items.size, from + cap)
        var to = from + 1
        var runtimeMs = items[from].runtimeMs
        while (to < limit && runtimeMs + items[to].runtimeMs <= epochMs) {
            runtimeMs += items[to].runtimeMs
            to++
        }
        return to
    }

    // The walk: in each round every group with items left takes a turn, which ends at [endOfTurn] (exclusive).
    private fun turns(
        groups: List<OrderGroup>,
        size: Int,
        endOfTurn: (OrderGroup, Int) -> Int,
    ): List<List<ScheduleItem>> {
        val taken = IntArray(groups.size)
        val turns = ArrayList<List<ScheduleItem>>()
        var remaining = size
        while (remaining > 0) {
            for ((index, group) in groups.withIndex()) {
                val from = taken[index]
                if (from >= group.items.size) continue
                val to = endOfTurn(group, from)
                turns += group.items.subList(from, to)
                taken[index] = to
                remaining -= to - from
            }
        }
        return turns
    }

    /**
     * The `inOrder` order of one cycle: every group (a series, or a film or series-less episode alone; no collections)
     * played start to finish in [inGroupKey] order, the groups ordered by:
     * 1. the undated flag (a group with no dated item last);
     * 2. the date of the group's first *dated* item in its own order, as text;
     * 3. the group ID bytes.
     *
     * It uses no seed and no caps.
     */
    fun inOrder(pool: List<ScheduleItem>): List<ScheduleItem> =
        pool
            .groupBy { it.groupId }
            .map { (groupId, items) ->
                val sorted = items.sortedWith(inGroupKey)
                InOrderGroup(groupId, sorted.firstNotNullOfOrNull { dateOf(it) }, sorted)
            }.sortedWith(
                Comparator { a, b ->
                    when {
                        (a.firstDate == null) != (b.firstDate == null) -> {
                            if (a.firstDate == null) 1 else -1
                        }

                        a.firstDate != null && b.firstDate != null && a.firstDate != b.firstDate -> {
                            a.firstDate.compareTo(b.firstDate)
                        }

                        else -> {
                            a.groupId.value.compareTo(b.groupId.value)
                        }
                    }
                },
            ).flatMap { it.items }

    private class InOrderGroup(
        val groupId: CanonicalId,
        val firstDate: String?,
        val items: List<ScheduleItem>,
    )

    /** The `randomInOrder` order of one cycle: the turns of [randomInOrderTurns], one after the other. */
    fun randomInOrder(
        pool: List<ScheduleItem>,
        seed: Seed,
        blockLength: Int,
    ): List<ScheduleItem> = randomInOrderTurns(pool, seed, blockLength).flatten()

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
    private const val SEASON_CLASS_REGULAR = 0
    private const val SEASON_CLASS_SPECIALS = 1
    private const val SEASON_CLASS_MISSING = 2
}
