// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.schedule

import tv.stationbreak.core.model.CanonicalId
import kotlin.jvm.JvmInline

/**
 * Which collection each film plays under (docs/rules-format.md, "Groups"): a side input to the day build, so
 * [ScheduleItem] stays as it is. A film in several of the collections a channel names is already resolved to the one
 * with the smallest collection ID; a film in none is not in the map.
 */
@JvmInline
value class FilmCollections private constructor(
    private val byFilm: Map<CanonicalId, CanonicalId>,
) {
    /** The collection [film] plays under, or null when it is a group of one. */
    operator fun get(film: CanonicalId): CanonicalId? = byFilm[film]

    val isEmpty: Boolean get() = byFilm.isEmpty()

    /** The (film, collection) pairs ascending by film ID bytes, whatever order the map was built in. */
    val entries: List<Pair<CanonicalId, CanonicalId>>
        get() = byFilm.entries.sortedBy { it.key.value }.map { it.key to it.value }

    companion object {
        fun of(map: Map<CanonicalId, CanonicalId>): FilmCollections = FilmCollections(map)
    }
}
