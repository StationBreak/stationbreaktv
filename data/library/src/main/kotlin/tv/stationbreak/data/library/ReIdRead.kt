// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.foldNameAscii

// Ids per read: each is bound under up to MAX_SPELLINGS spellings, with the account's two keys beside them.
private const val GONE_CHUNK = (LibraryDao.MAX_VARIABLES - 2) / MAX_SPELLINGS

/**
 * Where each of the tombstoned [gone] items came back (spec 26): [ReIdMatch] over the tombstones' signals and the live
 * movies and episodes of [libraries], with the series of the gone episodes read as well. A gone id with no row, or
 * with a live one, is not read as gone and so is absent. Every read is chunked under the bind limit; the provider ids
 * and names stay in memory and are never logged.
 */
@Suppress("LongParameterList", "ReturnCount") // The two DAOs and the account's keys; each empty read ends it early.
internal suspend fun reIdSuccessors(
    items: LibraryDao,
    series: SeriesDao,
    server: String,
    user: String,
    libraries: Set<String>,
    gone: Set<CanonicalId>,
): Map<CanonicalId, CanonicalId> {
    if (gone.isEmpty() || libraries.isEmpty()) return emptyMap()
    val goneItems = HashMap<CanonicalId, Signals>()
    for (chunk in gone.map { it.value }.chunked(GONE_CHUNK)) {
        for (row in items.goneSignalRows(server, user, chunk.flatMap(::idSpellings))) {
            val signals = row.toSignals() ?: continue
            if (signals.id in gone) goneItems[signals.id] = signals
        }
    }
    if (goneItems.isEmpty()) return emptyMap()
    val live = ArrayList<Signals>()
    val liveSeries = ArrayList<SeriesSignals>()
    for (chunk in libraries.chunked(LibraryDao.MAX_VARIABLES - 2)) {
        items.liveSignalRows(server, user, chunk).mapNotNullTo(live) { it.toSignals() }
        series.liveSeriesSignalRows(server, user, chunk).mapNotNullTo(liveSeries) { it.toSignals() }
    }
    val wantedSeries = goneItems.values.mapNotNullTo(LinkedHashSet()) { it.seriesId?.value }
    val goneSeries = ArrayList<SeriesSignals>()
    for (chunk in wantedSeries.chunked(GONE_CHUNK)) {
        val rows = series.goneSeriesSignalRows(server, user, chunk.flatMap(::idSpellings))
        rows.mapNotNullTo(goneSeries) { it.toSignals() }
    }
    return ReIdMatch.match(goneItems.values.toList(), live, liveSeries, goneSeries)
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
