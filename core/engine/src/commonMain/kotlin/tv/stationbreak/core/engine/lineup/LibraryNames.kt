// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.Source

/**
 * The names a library holds, which a themed recipe is matched against: collections by name (from [collections]),
 * studios, and titles (series and films) from [snapshot]. Every answer is in a fixed order, so equal inputs in any
 * order give equal answers. Names are folded once each, here, on the first call that needs them, and the folded
 * names are reused by every later call; no regex.
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

    // The folded studio names (studio clean, then ASCII fold) held by items and series, once each, in ascending order.
    private val studioKeys: List<String> by lazy {
        val out = HashSet<String>()
        for (entry in snapshot.items) addStudios(out, entry.studios)
        for (series in snapshot.series.values) addStudios(out, series.studios)
        out.sorted()
    }

    // The collections in [collections]' ID order with their folded names; a null name is not held.
    private val collectionNames: List<Pair<CanonicalId, String>> by lazy {
        val out = ArrayList<Pair<CanonicalId, String>>()
        for (id in collections.ids) {
            val name = collections[id]
            if (name != null) out.add(id to NameMatcher.fold(name))
        }
        out
    }

    /** The IDs of the collections whose name [match]es, in ascending ID order. */
    fun collections(match: NameMatcher): List<CanonicalId> {
        val out = ArrayList<CanonicalId>()
        for (pair in collectionNames) if (match.matches(pair.second)) out.add(pair.first)
        return out
    }

    /**
     * The studios [match]es, in ascending order. Each is the folded name ([NameMatcher.fold]), not a spelling the
     * library holds: a studio source matches under the same fold, so what airs is the same, and the rules do not change
     * when an item with another spelling of a studio is added.
     */
    fun studios(match: NameMatcher): List<String> {
        val keys = ArrayList<String>()
        for (key in studioKeys) if (match.matches(key)) keys.add(key)
        return keys
    }

    /** A series source for every series whose name [match]es, then an item source for every film, each in ID order. */
    fun titles(match: NameMatcher): List<Source> {
        val out = ArrayList<Source>()
        for (pair in seriesNames) if (match.matches(pair.second)) out.add(Source.Series(pair.first))
        for (pair in filmNames) if (match.matches(pair.second)) out.add(Source.Item(pair.first))
        return out
    }

    private fun addStudios(
        into: HashSet<String>,
        names: List<String>,
    ) {
        for (raw in names) {
            val key = NameMatcher.fold(raw)
            if (key.isNotEmpty()) into.add(key)
        }
    }
}
