// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.foldNameAscii

// Ids per read: each is bound under up to MAX_SPELLINGS spellings, with the account's keys and the time beside them.
private const val GONE_CHUNK = (LibraryDao.MAX_VARIABLES - 3) / MAX_SPELLINGS

// More term chunks than this and the narrowed reads would each scan the libraries again: one read of all is cheaper.
private const val MAX_TERM_CHUNKS = 2

// Libraries per live read: the rest of the 400 binds are for the terms.
private const val LIBRARY_CHUNK = 90
private const val TERM_CHUNK = LibraryDao.MAX_VARIABLES - LIBRARY_CHUNK - 10

// What the live read looks for, by the list it goes into.
private class Terms(
    val seriesIds: MutableSet<String> = LinkedHashSet(),
    val names: MutableSet<String> = LinkedHashSet(),
    val imdb: MutableSet<String> = LinkedHashSet(),
    val tmdb: MutableSet<String> = LinkedHashSet(),
    val tvdb: MutableSet<String> = LinkedHashSet(),
) {
    fun idsOf(providerIds: Map<String, String>) {
        providerIds["imdb"]?.let(imdb::add)
        providerIds["tmdb"]?.let(tmdb::add)
        providerIds["tvdb"]?.let(tvdb::add)
    }

    // The terms in chunks of at most [size] values in all, one list each.
    fun chunked(size: Int): List<Terms> {
        val all = ArrayList<Pair<Int, String>>()
        listOf(seriesIds, names, imdb, tmdb, tvdb).forEachIndexed { at, list -> list.mapTo(all) { at to it } }
        if (all.isEmpty()) return emptyList()
        return all.chunked(size).map { chunk ->
            Terms().also { out ->
                val lists = listOf(out.seriesIds, out.names, out.imdb, out.tmdb, out.tvdb)
                for ((at, value) in chunk) lists[at] += value
            }
        }
    }
}

/**
 * Where each of the tombstoned [gone] items came back (spec 26): [ReIdMatch] over the tombstones' signals (deleted at
 * or after [sinceMs]) and the live movies and episodes of [libraries] the gone items could have come back as, with the
 * series of the gone episodes read as well. The live read is narrowed in SQL to the series, the titles and the identity
 * ids the gone items have, and the rules check every row it gives. A gone id with no row, or with a live one, is not
 * read as gone and so is absent. Every read is chunked under the bind limit; the provider ids and names stay in memory
 * and are never logged. When the terms would take more than [MAX_TERM_CHUNKS] reads of the libraries, one read of every
 * live movie and episode replaces them. [trace] (a test seam, null in the app) hears each read: "series",
 * "live:narrowed" or "live:all".
 */
@Suppress("LongParameterList", "ReturnCount") // The two DAOs and the account's keys; an empty read ends it early.
internal suspend fun reIdSuccessors(
    items: LibraryDao,
    series: SeriesDao,
    server: String,
    user: String,
    libraries: Set<String>,
    gone: Set<CanonicalId>,
    sinceMs: Long,
    trace: ((String) -> Unit)? = null,
): Map<CanonicalId, CanonicalId> {
    if (gone.isEmpty() || libraries.isEmpty()) return emptyMap()
    val goneItems = readGone(items, server, user, gone, sinceMs)
    if (goneItems.isEmpty()) return emptyMap()
    val goneSeries = ArrayList<SeriesSignals>()
    val wantedSeries = goneItems.mapNotNullTo(LinkedHashSet()) { it.seriesId?.value }
    for (chunk in wantedSeries.chunked(GONE_CHUNK)) {
        series
            .goneSeriesSignalRows(server, user, sinceMs, chunk.flatMap(::idSpellings))
            .mapNotNullTo(goneSeries) { it.toSignals() }
    }
    val liveSeries = ArrayList<SeriesSignals>()
    // No gone series, no series to find again: the live ones are not read.
    if (goneSeries.isNotEmpty()) {
        trace?.invoke("series")
        for (chunk in libraries.chunked(LIBRARY_CHUNK)) {
            series.liveSeriesSignalRows(server, user, chunk).mapNotNullTo(liveSeries) { it.toSignals() }
        }
    }
    val terms = Terms()
    for (item in goneItems) {
        terms.idsOf(item.providerIds)
        item.seriesId?.let { terms.seriesIds += idSpellings(it.value) }
        if (item.kind == "Movie" && item.foldedName.isNotBlank()) terms.names += item.foldedName
    }
    for (moved in ReIdMatch.movedSeries(goneSeries, liveSeries)) terms.seriesIds += idSpellings(moved.value)
    val live = readLive(items, server, user, libraries, terms, trace)
    return ReIdMatch.match(goneItems, live, liveSeries, goneSeries)
}

// The tombstones among [gone], by canonical id (a row may be stored under any spelling of its id).
private suspend fun readGone(
    items: LibraryDao,
    server: String,
    user: String,
    gone: Set<CanonicalId>,
    sinceMs: Long,
): List<Signals> {
    val found = HashMap<CanonicalId, Signals>()
    for (chunk in gone.map { it.value }.chunked(GONE_CHUNK)) {
        for (row in items.goneSignalRows(server, user, sinceMs, chunk.flatMap(::idSpellings))) {
            val signals = row.toSignals() ?: continue
            if (signals.id in gone) found[signals.id] = signals
        }
    }
    return found.values.toList()
}

// The live candidates of [libraries] the [terms] name, in chunks of libraries and of terms; every live one when the
// terms are more than MAX_TERM_CHUNKS chunks.
@Suppress("LongParameterList") // The DAO, the account's keys, what to read and the test seam.
private suspend fun readLive(
    items: LibraryDao,
    server: String,
    user: String,
    libraries: Set<String>,
    terms: Terms,
    trace: ((String) -> Unit)?,
): List<Signals> {
    val live = HashMap<CanonicalId, Signals>()
    val termChunks = terms.chunked(TERM_CHUNK)
    val everything = termChunks.size > MAX_TERM_CHUNKS
    for (libs in libraries.chunked(LIBRARY_CHUNK)) {
        if (everything) {
            trace?.invoke("live:all")
            for (row in items.allLiveSignalRows(server, user, libs)) row.toSignals()?.let { live[it.id] = it }
            continue
        }
        for (t in termChunks) {
            trace?.invoke("live:narrowed")
            val rows =
                items.liveSignalRows(
                    server,
                    user,
                    libs,
                    t.seriesIds.toList(),
                    t.names.toList(),
                    t.imdb.toList(),
                    t.tmdb.toList(),
                    t.tvdb.toList(),
                )
            for (row in rows) row.toSignals()?.let { live[it.id] = it }
        }
    }
    return live.values.toList()
}

private fun SignalRow.toSignals(): Signals? {
    val id = CanonicalId.parseOrNull(itemId) ?: return null
    return Signals(
        id = id,
        kind = kind,
        providerIds = ProviderIdsCodec.decode(providerIds),
        seriesId = seriesId?.let { CanonicalId.parseOrNull(it) },
        season = seasonNumber,
        episode = episodeNumber,
        foldedName = foldNameAscii(name),
        year = productionYear,
        runtimeMs = runtimeMs,
    )
}

private fun SeriesSignalRow.toSignals(): SeriesSignals? {
    val id = CanonicalId.parseOrNull(seriesId) ?: return null
    return SeriesSignals(id, foldNameAscii(name), ProviderIdsCodec.decode(providerIds))
}
