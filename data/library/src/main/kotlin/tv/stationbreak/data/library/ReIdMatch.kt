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
 * The conservative match of an item that left the library and came back under a new id (spec 26). For each gone item
 * the rules run in order, and the first that finds any candidate of the same kind decides: exactly one candidate that
 * also passes the runtime check is the match, and more than one (or one that fails the runtime check) is no match at
 * all, never a pick. A rule that finds none leaves it to the next.
 *
 * 1. A shared provider id pair (the same key with the same value).
 * 2. An episode: the same series, and the same season and episode. The series is the same when the id is the same, or
 *    when the gone series is a tombstone and exactly one live series shares a provider id pair with it, or, failing
 *    any such series, exactly one live series has its folded name (never an empty one).
 * 3. A film: the same folded name (never empty) and the same year (never unknown).
 *
 * The result is one-to-one: a candidate two gone items both chose is the match of neither. The rules and the holds
 * are the repairer's to check, not this match's. Pure: it reads no store and logs nothing.
 */
internal object ReIdMatch {
    private const val MIN_TOLERANCE_MS = 2 * 60_000L
    private const val TOLERANCE_PERCENT = 2L

    private const val EPISODE = "Episode"
    private const val MOVIE = "Movie"

    fun match(
        gone: List<Signals>,
        live: List<Signals>,
        liveSeries: List<SeriesSignals>,
        goneSeries: List<SeriesSignals>,
    ): Map<CanonicalId, CanonicalId> {
        if (gone.isEmpty() || live.isEmpty()) return emptyMap()
        val goneSeriesById = goneSeries.associateBy { it.id }
        val choices = LinkedHashMap<CanonicalId, CanonicalId>()
        for (item in gone) {
            val chosen = choose(item, live, liveSeries, goneSeriesById) ?: continue
            choices[item.id] = chosen
        }
        val claims = choices.values.groupingBy { it }.eachCount()
        val result = LinkedHashMap<CanonicalId, CanonicalId>()
        for ((from, to) in choices) if (claims.getValue(to) == 1) result[from] = to
        return result
    }

    private fun choose(
        item: Signals,
        live: List<Signals>,
        liveSeries: List<SeriesSignals>,
        goneSeries: Map<CanonicalId, SeriesSignals>,
    ): CanonicalId? {
        val sameKind = live.filter { it.kind == item.kind && it.id != item.id }
        val only = firstFinding(item, sameKind, liveSeries, goneSeries)?.singleOrNull() ?: return null
        return only.id.takeIf { withinRuntime(item, only) }
    }

    // The candidates of the first rule that finds any, or null when no rule does.
    private fun firstFinding(
        item: Signals,
        candidates: List<Signals>,
        liveSeries: List<SeriesSignals>,
        goneSeries: Map<CanonicalId, SeriesSignals>,
    ): List<Signals>? =
        sequenceOf(
            { byProviderId(item, candidates) },
            { if (item.kind == EPISODE) byEpisode(item, candidates, liveSeries, goneSeries) else null },
            { if (item.kind == MOVIE) byFilm(item, candidates) else null },
        ).mapNotNull { rule -> rule()?.takeIf { it.isNotEmpty() } }.firstOrNull()

    private fun byProviderId(
        item: Signals,
        candidates: List<Signals>,
    ): List<Signals>? {
        if (item.providerIds.isEmpty()) return null
        return candidates.filter { sharesPair(item.providerIds, it.providerIds) }
    }

    private fun byEpisode(
        item: Signals,
        candidates: List<Signals>,
        liveSeries: List<SeriesSignals>,
        goneSeries: Map<CanonicalId, SeriesSignals>,
    ): List<Signals>? {
        val seriesId = item.seriesId
        val season = item.season
        val episode = item.episode
        if (seriesId == null || season == null || episode == null) return null
        val moved = goneSeries[seriesId]?.let { liveSeriesOf(it, liveSeries) }
        return candidates.filter {
            it.season == season && it.episode == episode &&
                it.seriesId != null && (it.seriesId == seriesId || it.seriesId == moved)
        }
    }

    // The one live series the tombstoned [gone] came back as, or null: by a shared provider id pair when exactly one
    // live series has one, and only when none does, by a folded name that exactly one live series has.
    private fun liveSeriesOf(
        gone: SeriesSignals,
        liveSeries: List<SeriesSignals>,
    ): CanonicalId? {
        val byIds = liveSeries.filter { sharesPair(gone.providerIds, it.providerIds) }
        return when {
            byIds.isNotEmpty() -> byIds.singleOrNull()?.id
            gone.foldedName.isEmpty() -> null
            else -> liveSeries.singleOrNull { it.foldedName == gone.foldedName }?.id
        }
    }

    private fun byFilm(
        item: Signals,
        candidates: List<Signals>,
    ): List<Signals>? {
        if (item.foldedName.isEmpty() || item.year == null) return null
        return candidates.filter { it.foldedName == item.foldedName && it.year == item.year }
    }

    private fun sharesPair(
        a: Map<String, String>,
        b: Map<String, String>,
    ): Boolean = a.any { (key, value) -> b[key] == value }

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
