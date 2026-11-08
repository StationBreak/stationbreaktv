// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.model.CanonicalId

/**
 * What the match reads of one item, live or gone (spec 26). [foldedName] is the name after `foldNameAscii`. The names
 * are text a person typed and the provider ids are never logged, so [toString] shows neither.
 */
internal data class Signals(
    val id: CanonicalId,
    val kind: String,
    val providerIds: Map<String, String>,
    val seriesId: CanonicalId?,
    val season: Int?,
    val episode: Int?,
    val foldedName: String,
    val year: Int?,
    val runtimeMs: Long?,
) {
    override fun toString(): String = "Signals(id=$id, kind=$kind)"
}

/** What the match reads of one series, live or a tombstone: the same rules as [Signals] for the text. */
internal data class SeriesSignals(
    val id: CanonicalId,
    val foldedName: String,
    val providerIds: Map<String, String>,
) {
    override fun toString(): String = "SeriesSignals(id=$id)"
}

/**
 * What [ReIdMatch] reads (spec 26): the tombstoned items' signals ([gone]), the live candidates ([live]), and the live
 * and tombstoned series ([liveSeries], [goneSeries]). [match] runs the match over them, outside any transaction.
 */
internal class ReIdRows(
    val gone: List<Signals>,
    val live: List<Signals>,
    val liveSeries: List<SeriesSignals>,
    val goneSeries: List<SeriesSignals>,
) {
    /** Where each gone item came back; empty when nothing was read. */
    fun match(): Map<CanonicalId, CanonicalId> = ReIdMatch.match(gone, live, liveSeries, goneSeries)

    companion object {
        val NONE = ReIdRows(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/**
 * The conservative match of an item that left the library and came back under a new id (spec 26). For each gone item
 * the rules run in order, and the first that finds any candidate of the same kind decides: exactly one usable candidate
 * that also passes the runtime check is the match, and more than one (or one that fails the runtime check, or that an
 * identity id contradicts) is no match at all, never a pick. A rule that finds none leaves it to the next.
 *
 * Identity ids are the provider ids `imdb`, `tmdb` and `tvdb` and no others (a `tmdbcollection` id names a set of
 * films, not one of them). Two items conflict when an identity key is on both with different values.
 *
 * 1. A shared identity pair, with no conflict.
 * 2. An episode: the same series, and the same season and episode (both known). The series is the same when the id is
 *    the same, or when the gone series is a tombstone and exactly one live series shares an identity pair with it (no
 *    live series sharing a pair is what lets the name decide, never several), or else exactly one live series has its
 *    folded name (never a blank one) and no conflicting identity id.
 * 3. A film: the same folded name (never blank) and the same year (never unknown), with no conflicting identity id.
 *
 * A gone item with more than one candidate also voids each of them for the others, so the result is one-to-one: a
 * candidate chosen by two gone items, or seen by an ambiguous one, is the match of none. The rules and the holds are
 * the repairer's to check, not this match's. Pure: it reads no store and logs nothing. It indexes the live rows and
 * series once per call (by kind and identity pair, by series, season and episode, and by folded name and year), so
 * each gone item looks its candidates up instead of reading every live row.
 */
@Suppress("TooManyFunctions") // One small function per rule and check.
internal object ReIdMatch {
    private const val MIN_TOLERANCE_MS = 2 * 60_000L
    private const val TOLERANCE_PERCENT = 2L

    private const val EPISODE = "Episode"
    private const val MOVIE = "Movie"

    /** The provider id keys that name one item or series; every other key is never matched. */
    val IDENTITY_KEYS: Set<String> = setOf("imdb", "tmdb", "tvdb")

    // What a gone item came to: its choice if any, and the candidates it saw when more than one fitted.
    private class Choice(
        val chosen: CanonicalId?,
        val voids: List<CanonicalId> = emptyList(),
    )

    // The candidates a rule found, and the ones of them an identity id does not contradict.
    private class Finding(
        val seen: List<Signals>,
        val usable: List<Signals>,
    )

    private data class EpisodeKey(
        val seriesId: CanonicalId,
        val season: Int,
        val episode: Int,
    )

    private data class FilmKey(
        val foldedName: String,
        val year: Int,
    )

    private data class IdentityKey(
        val kind: String,
        val key: String,
        val value: String,
    )

    // The live rows, each list keyed once: an identity pair by kind, an episode by its series and numbers, a film by
    // its folded name and year.
    private class LiveItems(
        live: List<Signals>,
    ) {
        val byIdentity = HashMap<IdentityKey, MutableList<Signals>>()
        val byEpisode = HashMap<EpisodeKey, MutableList<Signals>>()
        val byFilm = HashMap<FilmKey, MutableList<Signals>>()

        init {
            for (row in live) {
                for ((key, value) in row.providerIds) {
                    if (key in IDENTITY_KEYS) byIdentity.getOrPut(IdentityKey(row.kind, key, value), ::ArrayList) += row
                }
                if (row.kind == EPISODE) episodeKey(row)?.let { byEpisode.getOrPut(it, ::ArrayList) += row }
                val year = row.year
                if (row.kind == MOVIE && year != null) {
                    byFilm.getOrPut(FilmKey(row.foldedName, year), ::ArrayList) += row
                }
            }
        }

        // An episode's series and numbers, when all three are known.
        private fun episodeKey(row: Signals): EpisodeKey? {
            val seriesId = row.seriesId
            val season = row.season
            val episode = row.episode
            val known = seriesId != null && season != null && episode != null
            return if (known) EpisodeKey(seriesId, season, episode) else null
        }
    }

    // The live series, keyed by identity pair and by folded name; [movedOf] answers each tombstoned series once.
    private class LiveSeries(
        liveSeries: List<SeriesSignals>,
    ) {
        private val byIdentity = HashMap<Pair<String, String>, MutableList<SeriesSignals>>()
        private val byName = HashMap<String, MutableList<SeriesSignals>>()
        private val moved = HashMap<CanonicalId, CanonicalId?>()

        init {
            for (series in liveSeries) {
                for ((key, value) in series.providerIds) {
                    if (key in IDENTITY_KEYS) byIdentity.getOrPut(key to value) { ArrayList() } += series
                }
                byName.getOrPut(series.foldedName) { ArrayList() } += series
            }
        }

        fun movedOf(gone: SeriesSignals): CanonicalId? = moved.getOrPut(gone.id) { liveSeriesOf(gone) }

        // The one live series the tombstoned [gone] came back as, or null: by a shared identity pair when exactly one
        // live series has one, and only when none does, by a folded name that exactly one live series has (an identity
        // id that disagrees rules a series out).
        @Suppress("ReturnCount") // Each way of finding the series gives up or answers at once.
        private fun liveSeriesOf(gone: SeriesSignals): CanonicalId? {
            val sharing = LinkedHashMap<CanonicalId, SeriesSignals>()
            for ((key, value) in gone.providerIds) {
                if (key !in IDENTITY_KEYS) continue
                for (series in byIdentity[key to value].orEmpty()) sharing.putIfAbsent(series.id, series)
            }
            val byIds = sharing.values.filter { sharesIdentity(gone.providerIds, it.providerIds) }
            if (byIds.isNotEmpty()) return byIds.singleOrNull()?.id
            if (gone.foldedName.isBlank()) return null
            val named = byName[gone.foldedName].orEmpty()
            val clean = named.filterNot { conflicts(gone.providerIds, it.providerIds) }
            // A contradicted series beside others of the name: which is it cannot be told.
            return if (named.size > 1 && clean.size < named.size) null else clean.singleOrNull()?.id
        }
    }

    fun match(
        gone: List<Signals>,
        live: List<Signals>,
        liveSeries: List<SeriesSignals>,
        goneSeries: List<SeriesSignals>,
    ): Map<CanonicalId, CanonicalId> {
        if (gone.isEmpty() || live.isEmpty()) return emptyMap()
        val items = LiveItems(live)
        val series = LiveSeries(liveSeries)
        val goneSeriesById = goneSeries.associateBy { it.id }
        val choices = LinkedHashMap<CanonicalId, CanonicalId>()
        val voided = HashSet<CanonicalId>()
        for (item in gone) {
            val choice = choose(item, items, series, goneSeriesById)
            choice.chosen?.let { choices[item.id] = it }
            voided += choice.voids
        }
        val claims = choices.values.groupingBy { it }.eachCount()
        val result = LinkedHashMap<CanonicalId, CanonicalId>()
        for ((from, to) in choices) if (claims.getValue(to) == 1 && to !in voided) result[from] = to
        return result
    }

    /** The live series each tombstoned series of [goneSeries] came back as (the ones [match] reads an episode by). */
    fun movedSeries(
        goneSeries: List<SeriesSignals>,
        liveSeries: List<SeriesSignals>,
    ): Set<CanonicalId> {
        val series = LiveSeries(liveSeries)
        return goneSeries.mapNotNullTo(HashSet()) { series.movedOf(it) }
    }

    @Suppress("ReturnCount") // Each way a gone item ends without a match returns at once.
    private fun choose(
        item: Signals,
        items: LiveItems,
        series: LiveSeries,
        goneSeries: Map<CanonicalId, SeriesSignals>,
    ): Choice {
        val usable = firstFinding(item, items, series, goneSeries)?.usable ?: return Choice(null)
        if (usable.size > 1) return Choice(null, usable.map { it.id })
        val only = usable.singleOrNull() ?: return Choice(null)
        return Choice(only.id.takeIf { withinRuntime(item, only) })
    }

    // The first rule that finds any candidate, or null when no rule does.
    private fun firstFinding(
        item: Signals,
        items: LiveItems,
        series: LiveSeries,
        goneSeries: Map<CanonicalId, SeriesSignals>,
    ): Finding? =
        sequenceOf(
            { byProviderId(item, items) },
            { if (item.kind == EPISODE) byEpisode(item, items, series, goneSeries) else null },
            { if (item.kind == MOVIE) byFilm(item, items) else null },
        ).mapNotNull { rule -> rule()?.takeIf { it.seen.isNotEmpty() } }.firstOrNull()

    // The live rows of [lists], each once, never [item] itself.
    private fun candidates(
        item: Signals,
        lists: List<List<Signals>?>,
    ): List<Signals> {
        val out = LinkedHashMap<CanonicalId, Signals>()
        for (list in lists) for (row in list.orEmpty()) if (row.id != item.id) out.putIfAbsent(row.id, row)
        return ArrayList(out.values)
    }

    private fun byProviderId(
        item: Signals,
        items: LiveItems,
    ): Finding? {
        if (item.providerIds.isEmpty()) return null
        val lists =
            item.providerIds.filterKeys { it in IDENTITY_KEYS }.map { (key, value) ->
                items.byIdentity[IdentityKey(item.kind, key, value)]
            }
        val found = candidates(item, lists).filter { sharesIdentity(item.providerIds, it.providerIds) }
        return Finding(found, found)
    }

    private fun byEpisode(
        item: Signals,
        items: LiveItems,
        series: LiveSeries,
        goneSeries: Map<CanonicalId, SeriesSignals>,
    ): Finding? {
        val seriesId = item.seriesId
        val season = item.season
        val episode = item.episode
        if (seriesId == null || season == null || episode == null) return null
        val moved = goneSeries[seriesId]?.let { series.movedOf(it) }
        val same = items.byEpisode[EpisodeKey(seriesId, season, episode)]
        val back = moved?.takeIf { it != seriesId }?.let { items.byEpisode[EpisodeKey(it, season, episode)] }
        val found = candidates(item, listOf(same, back))
        return Finding(found, usable(item.providerIds, found))
    }

    private fun byFilm(
        item: Signals,
        items: LiveItems,
    ): Finding? {
        val year = item.year
        if (item.foldedName.isBlank() || year == null) return null
        val found = candidates(item, listOf(items.byFilm[FilmKey(item.foldedName, year)]))
        return Finding(found, usable(item.providerIds, found))
    }

    // The [found] candidates no identity id contradicts. When one was contradicted and others were found, which of them
    // the gone item is cannot be told: none is usable.
    private fun usable(
        ids: Map<String, String>,
        found: List<Signals>,
    ): List<Signals> {
        val clean = found.filterNot { conflicts(ids, it.providerIds) }
        return if (found.size > 1 && clean.size < found.size) emptyList() else clean
    }

    // At least one identity key with the same value on both sides, and no identity key with different values.
    private fun sharesIdentity(
        a: Map<String, String>,
        b: Map<String, String>,
    ): Boolean = a.any { (key, value) -> key in IDENTITY_KEYS && b[key] == value } && !conflicts(a, b)

    private fun conflicts(
        a: Map<String, String>,
        b: Map<String, String>,
    ): Boolean = a.any { (key, value) -> key in IDENTITY_KEYS && b[key]?.let { it != value } == true }

    // |runtime - goneRuntime| <= max(2 min, 2% of goneRuntime); an unknown runtime on either side fails it.
    private fun withinRuntime(
        gone: Signals,
        candidate: Signals,
    ): Boolean {
        val was = gone.runtimeMs
        val now = candidate.runtimeMs
        if (was == null || now == null) return false
        val tolerance = maxOf(MIN_TOLERANCE_MS, was * TOLERANCE_PERCENT / 100)
        return kotlin.math.abs(now - was) <= tolerance
    }
}
