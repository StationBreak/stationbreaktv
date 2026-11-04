// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.cleanStudioName
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.Source

/**
 * The names a library holds, which a themed recipe is matched against: collections by name (from [collections]),
 * studios, and titles (series and films) from [snapshot]. Every answer is in a fixed order, so equal inputs in any
 * order give equal answers. Names are folded once each, here; no regex.
 */
class LibraryNames(
    private val snapshot: RuleSnapshot,
    private val collections: CollectionNames,
) {
    // Series, then films, each in ID order; a null name is not held.
    private val seriesNames: List<Pair<CanonicalId, String>> by lazy {
        val out = ArrayList<Pair<CanonicalId, String>>()
        for (entry in snapshot.series.entries.sortedBy { it.key.value }) {
            val name = entry.value.name
            if (name != null) out.add(entry.key to NameMatcher.fold(name))
        }
        out
    }

    private val filmNames: List<Pair<CanonicalId, String>> by lazy {
        val out = ArrayList<Pair<CanonicalId, String>>()
        for (entry in snapshot.items) {
            val name = entry.name
            if (name == null || entry.item.kind != ScheduleItemKind.Movie) continue
            out.add(entry.item.id to NameMatcher.fold(name))
        }
        out.sortBy { it.first.value }
        out
    }

    // Folded studio name to one spelling of it (the smallest), so the answer does not depend on item order.
    private val studioSpellings: Map<String, String> by lazy {
        val out = HashMap<String, String>()
        for (entry in snapshot.items) addStudios(out, entry.studios)
        for (series in snapshot.series.values) addStudios(out, series.studios)
        out
    }

    /** The IDs of the collections whose name [match]es, in ascending ID order. */
    fun collections(match: NameMatcher): List<CanonicalId> {
        val out = ArrayList<CanonicalId>()
        for (id in collections.ids) {
            val name = collections[id]
            if (name != null && match.matches(NameMatcher.fold(name))) out.add(id)
        }
        return out
    }

    /** The studio names [match]es, one per folded spelling, in ascending order of the folded name. */
    fun studios(match: NameMatcher): List<String> {
        val keys = ArrayList<String>()
        for (key in studioSpellings.keys) if (match.matches(key)) keys.add(key)
        keys.sort()
        val out = ArrayList<String>(keys.size)
        for (key in keys) out.add(studioSpellings.getValue(key))
        return out
    }

    /** A series source for every series whose name [match]es, then an item source for every film, each in ID order. */
    fun titles(match: NameMatcher): List<Source> {
        val out = ArrayList<Source>()
        for (pair in seriesNames) if (match.matches(pair.second)) out.add(Source.Series(pair.first))
        for (pair in filmNames) if (match.matches(pair.second)) out.add(Source.Item(pair.first))
        return out
    }

    private fun addStudios(
        into: HashMap<String, String>,
        names: List<String>,
    ) {
        for (raw in names) {
            val cleaned = cleanStudioName(raw)
            if (cleaned.isEmpty()) continue
            val key = NameMatcher.fold(cleaned)
            val held = into[key]
            if (held == null || cleaned < held) into[key] = cleaned
        }
    }
}
