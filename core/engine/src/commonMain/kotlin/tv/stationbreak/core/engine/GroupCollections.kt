// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source

/** The film-to-collection side input of a day build (docs/rules-format.md, "Groups in version 2"). */
object GroupCollections {
    /**
     * Film ID to collection ID for the films of [pool] that are member movies of a collection named in
     * `rules.sources`, each under the smallest such collection ID (compared as its 32 ASCII bytes). A collection that
     * is only an exclusion, one the channel does not name, and one missing from [snapshot] group nothing; episodes are
     * never in the map. The map is empty unless the mode reads it: `rotation` or a capped `shuffle`.
     */
    fun of(
        rules: ChannelRules,
        snapshot: RuleSnapshot,
        pool: List<ScheduleItem>,
    ): FilmCollections {
        if (rules.effectiveCaps == null) return FilmCollections.of(emptyMap())
        val films = HashSet<CanonicalId>()
        for (item in pool) if (item.kind == ScheduleItemKind.Movie) films.add(item.id)
        val named =
            rules.sources
                .filterIsInstance<Source.Collection>()
                .map { it.id }
                .distinctBy { it.value }
        val map = HashMap<CanonicalId, CanonicalId>()
        // Smallest ID first, so the first collection to claim a film is the smallest one that has it.
        for (id in named.sortedBy { it.value }) {
            val members = snapshot.collections[id] ?: continue
            for (film in members.movies) if (film in films && film !in map) map[film] = id
        }
        return FilmCollections.of(map)
    }
}
