// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.Caps
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind

/** What kind of group an item belongs to in the version 2 orders. */
internal enum class GroupKind { Series, Collection, Single }

/**
 * Which group an item belongs to in the version 2 orders (docs/rules-format.md, "Groups"): a series, a collection of
 * films, or the item alone. [films] maps a film to its collection, already resolved to the smallest named collection
 * ID, so no rule here depends on the rest of the pool.
 */
internal class GroupRules(
    private val films: FilmCollections,
) {
    /** The series of an episode, else the film's collection, else the item's own ID. */
    fun groupId(item: ScheduleItem): CanonicalId = item.seriesId ?: collectionOf(item) ?: item.id

    fun kind(item: ScheduleItem): GroupKind =
        when {
            item.seriesId != null -> GroupKind.Series
            collectionOf(item) != null -> GroupKind.Collection
            else -> GroupKind.Single
        }

    /** Only a film is in a collection: another item stays on its own, whatever the map says. */
    private fun collectionOf(item: ScheduleItem): CanonicalId? {
        val isFilm = item.kind == ScheduleItemKind.Movie
        return if (isFilm) films[item.id] else null
    }

    /** How many items of the group may play in a row: the series cap, the film cap, or 1 for an item on its own. */
    fun cap(
        item: ScheduleItem,
        caps: Caps,
    ): Int =
        when (kind(item)) {
            GroupKind.Series -> caps.series
            GroupKind.Collection -> caps.film
            GroupKind.Single -> 1
        }
}
