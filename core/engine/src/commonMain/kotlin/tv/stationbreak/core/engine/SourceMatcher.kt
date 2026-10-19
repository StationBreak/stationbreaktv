// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.Source

/**
 * Matches items against a list of sources or exclusions (docs/rules-format.md, "Items and the pool", rule 2). It is
 * built once from the list and the snapshot, so a lookup per item costs the item's own names and no loop over the
 * rules, except the year ranges.
 *
 * Every lookup table maps a key to the indices (into the list given) of the sources that key selects, so one
 * structure answers both "does anything match" and "which sources matched".
 *
 * It is not thread-safe: [tally] keeps a scratch stamp so that one source is counted once per item. The per-item
 * methods use plain loops and allocate nothing (no lambdas in the per-item path: ART 7.1 crash, #264).
 */
internal class SourceMatcher(
    sources: List<Source>,
    snapshot: RuleSnapshot,
) {
    private val byLibrary: Map<String, IntArray>
    private val bySeries: Map<String, IntArray>
    private val movieMembers: Map<String, IntArray>
    private val episodeMembers: Map<String, IntArray>
    private val seriesMembers: Map<String, IntArray>
    private val seasonMembers: Map<String, IntArray>
    private val genres: NameSet
    private val tags: NameSet
    private val yearFrom: IntArray
    private val yearTo: IntArray
    private val yearSource: IntArray

    private val stamped = IntArray(sources.size)
    private var stamp = 0

    init {
        val library = Index()
        val series = Index()
        val movies = Index()
        val episodes = Index()
        val seriesOfCollection = Index()
        val seasons = Index()
        val genreNames = ArrayList<String>()
        val genreSources = ArrayList<Int>()
        val tagNames = ArrayList<String>()
        val tagSources = ArrayList<Int>()
        val from = ArrayList<Int>()
        val to = ArrayList<Int>()
        val years = ArrayList<Int>()
        for (index in sources.indices) {
            when (val source = sources[index]) {
                is Source.Library -> {
                    library.add(source.id.value, index)
                }

                is Source.Series -> {
                    series.add(source.id.value, index)
                }

                is Source.Genre -> {
                    genreNames.add(source.name)
                    genreSources.add(index)
                }

                is Source.Tag -> {
                    tagNames.add(source.name)
                    tagSources.add(index)
                }

                is Source.YearRange -> {
                    from.add(source.from)
                    to.add(source.to)
                    years.add(index)
                }

                is Source.Collection -> {
                    // A collection that is not in the snapshot selects nothing.
                    val members = snapshot.collections[source.id]
                    if (members != null) {
                        for (id in members.movies) movies.add(id.value, index)
                        for (id in members.episodes) episodes.add(id.value, index)
                        for (id in members.series) seriesOfCollection.add(id.value, index)
                        for (id in members.seasons) seasons.add(id.value, index)
                    }
                }
            }
        }
        byLibrary = library.freeze()
        bySeries = series.freeze()
        movieMembers = movies.freeze()
        episodeMembers = episodes.freeze()
        seriesMembers = seriesOfCollection.freeze()
        seasonMembers = seasons.freeze()
        genres = NameSet(genreNames, genreSources.toIntArray())
        tags = NameSet(tagNames, tagSources.toIntArray())
        yearFrom = from.toIntArray()
        yearTo = to.toIntArray()
        yearSource = years.toIntArray()
    }

    /** True if [item] matches any source. [series] is the item's series when it has one in the snapshot, else null. */
    fun matches(
        item: RuleItem,
        series: RuleSeries?,
    ): Boolean = scan(item, series, null)

    /**
     * Adds one to `hits[i]` for every source `i` that [item] matches (a source counts once per item, however many of
     * its names or members the item has), and returns true if any matched. [hits] has one slot per source.
     */
    fun tally(
        item: RuleItem,
        series: RuleSeries?,
        hits: IntArray,
    ): Boolean {
        stamp++
        return scan(item, series, hits)
    }

    // With hits == null the first match is enough and the scan stops. Otherwise it goes on, so every source is counted.
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // One flat sequence of lookups, one per rule 2 line.
    private fun scan(
        item: RuleItem,
        series: RuleSeries?,
        hits: IntArray?,
    ): Boolean {
        val schedule = item.item
        val isEpisode = schedule.kind == ScheduleItemKind.Episode
        var any = false

        if (mark(byLibrary[item.libraryId.value], hits)) {
            if (hits == null) return true
            any = true
        }
        val seriesId = schedule.seriesId
        if (isEpisode && seriesId != null) {
            if (mark(bySeries[seriesId.value], hits)) {
                if (hits == null) return true
                any = true
            }
            if (mark(seriesMembers[seriesId.value], hits)) {
                if (hits == null) return true
                any = true
            }
        }
        val seasonId = item.seasonId
        if (isEpisode && seasonId != null && mark(seasonMembers[seasonId.value], hits)) {
            if (hits == null) return true
            any = true
        }
        val memberKind = if (isEpisode) episodeMembers else movieMembers
        if (mark(memberKind[schedule.id.value], hits)) {
            if (hits == null) return true
            any = true
        }
        val inherited = if (isEpisode) series else null
        if (markNames(genres, item.genres, inherited?.genres, hits)) {
            if (hits == null) return true
            any = true
        }
        if (markNames(tags, item.tags, inherited?.tags, hits)) {
            if (hits == null) return true
            any = true
        }
        val year = schedule.productionYear
        if (year != null) {
            for (i in yearSource.indices) {
                if (year >= yearFrom[i] && year <= yearTo[i]) {
                    if (hits == null) return true
                    count(yearSource[i], hits)
                    any = true
                }
            }
        }
        return any
    }

    private fun markNames(
        set: NameSet,
        own: List<String>,
        inherited: List<String>?,
        hits: IntArray?,
    ): Boolean {
        if (set.isEmpty) return false
        val fromOwn = markList(set, own, hits)
        // Without counts the first match is enough; with them the series' names are still tallied.
        val fromSeries = (hits != null || !fromOwn) && inherited != null && markList(set, inherited, hits)
        return fromOwn || fromSeries
    }

    private fun markList(
        set: NameSet,
        names: List<String>,
        hits: IntArray?,
    ): Boolean {
        var any = false
        for (i in names.indices) {
            if (mark(set.indicesOf(names[i]), hits)) {
                if (hits == null) return true
                any = true
            }
        }
        return any
    }

    // True if [indices] holds any source. With [hits] it also counts each of them once for the current item.
    private fun mark(
        indices: IntArray?,
        hits: IntArray?,
    ): Boolean {
        if (indices == null) return false
        if (hits != null) {
            for (i in indices.indices) count(indices[i], hits)
        }
        return true
    }

    private fun count(
        source: Int,
        hits: IntArray,
    ) {
        if (stamped[source] != stamp) {
            stamped[source] = stamp
            hits[source]++
        }
    }

    // Builds a key -> source indices table. Used only while the matcher is built.
    private class Index {
        private val building = HashMap<String, MutableList<Int>>()

        fun add(
            key: String,
            source: Int,
        ) {
            building.getOrPut(key) { ArrayList(1) }.add(source)
        }

        fun freeze(): Map<String, IntArray> {
            val frozen = HashMap<String, IntArray>(building.size * 2)
            for ((key, indices) in building) frozen[key] = indices.toIntArray()
            return frozen
        }
    }
}
