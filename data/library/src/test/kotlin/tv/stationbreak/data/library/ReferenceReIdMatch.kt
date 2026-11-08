// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.model.CanonicalId

/**
 * The re-id match as Task 3.3 built it (spec 26), one filter of every live row per gone item: the reference the indexed
 * [ReIdMatch] must agree with on every input (the whole-part fix kept its rules and changed only how it finds the
 * candidates). Test code only.
 */
@Suppress("TooManyFunctions") // One small function per rule and check.
internal object ReferenceReIdMatch {
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

    fun match(
        gone: List<Signals>,
        live: List<Signals>,
        liveSeries: List<SeriesSignals>,
        goneSeries: List<SeriesSignals>,
    ): Map<CanonicalId, CanonicalId> {
        if (gone.isEmpty() || live.isEmpty()) return emptyMap()
        val goneSeriesById = goneSeries.associateBy { it.id }
        val choices = LinkedHashMap<CanonicalId, CanonicalId>()
        val voided = HashSet<CanonicalId>()
        for (item in gone) {
            val choice = choose(item, live, liveSeries, goneSeriesById)
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
    ): Set<CanonicalId> = goneSeries.mapNotNullTo(HashSet()) { liveSeriesOf(it, liveSeries) }

    @Suppress("ReturnCount") // Each way a gone item ends without a match returns at once.
    private fun choose(
        item: Signals,
        live: List<Signals>,
        liveSeries: List<SeriesSignals>,
        goneSeries: Map<CanonicalId, SeriesSignals>,
    ): Choice {
        val sameKind = live.filter { it.kind == item.kind && it.id != item.id }
        val usable = firstFinding(item, sameKind, liveSeries, goneSeries)?.usable ?: return Choice(null)
        if (usable.size > 1) return Choice(null, usable.map { it.id })
        val only = usable.singleOrNull() ?: return Choice(null)
        return Choice(only.id.takeIf { withinRuntime(item, only) })
    }

    // The first rule that finds any candidate, or null when no rule does.
    private fun firstFinding(
        item: Signals,
        candidates: List<Signals>,
        liveSeries: List<SeriesSignals>,
        goneSeries: Map<CanonicalId, SeriesSignals>,
    ): Finding? =
        sequenceOf(
            { byProviderId(item, candidates) },
            { if (item.kind == EPISODE) byEpisode(item, candidates, liveSeries, goneSeries) else null },
            { if (item.kind == MOVIE) byFilm(item, candidates) else null },
        ).mapNotNull { rule -> rule()?.takeIf { it.seen.isNotEmpty() } }.firstOrNull()

    private fun byProviderId(
        item: Signals,
        candidates: List<Signals>,
    ): Finding? {
        if (item.providerIds.isEmpty()) return null
        val found = candidates.filter { sharesIdentity(item.providerIds, it.providerIds) }
        return Finding(found, found)
    }

    private fun byEpisode(
        item: Signals,
        candidates: List<Signals>,
        liveSeries: List<SeriesSignals>,
        goneSeries: Map<CanonicalId, SeriesSignals>,
    ): Finding? {
        val seriesId = item.seriesId
        val season = item.season
        val episode = item.episode
        if (seriesId == null || season == null || episode == null) return null
        val moved = goneSeries[seriesId]?.let { liveSeriesOf(it, liveSeries) }
        val found =
            candidates.filter {
                it.season == season && it.episode == episode &&
                    it.seriesId != null && (it.seriesId == seriesId || it.seriesId == moved)
            }
        return Finding(found, usable(item.providerIds, found))
    }

    // The one live series the tombstoned [gone] came back as, or null: by a shared identity pair when exactly one live
    // series has one, and only when none does, by a folded name that exactly one live series has (an identity id that
    // disagrees rules a series out).
    @Suppress("ReturnCount") // Each way of finding the series gives up or answers at once.
    private fun liveSeriesOf(
        gone: SeriesSignals,
        liveSeries: List<SeriesSignals>,
    ): CanonicalId? {
        val byIds = liveSeries.filter { sharesIdentity(gone.providerIds, it.providerIds) }
        if (byIds.isNotEmpty()) return byIds.singleOrNull()?.id
        if (gone.foldedName.isBlank()) return null
        val named = liveSeries.filter { it.foldedName == gone.foldedName }
        val clean = named.filterNot { conflicts(gone.providerIds, it.providerIds) }
        // A contradicted series beside others of the name: which is it cannot be told.
        return if (named.size > 1 && clean.size < named.size) null else clean.singleOrNull()?.id
    }

    private fun byFilm(
        item: Signals,
        candidates: List<Signals>,
    ): Finding? {
        if (item.foldedName.isBlank() || item.year == null) return null
        val found = candidates.filter { it.foldedName == item.foldedName && it.year == item.year }
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
