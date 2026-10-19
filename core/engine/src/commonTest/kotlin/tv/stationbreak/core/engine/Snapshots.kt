// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.Timing

private const val HEX_RADIX = 16
private const val ID_LENGTH = 32
private const val RUNTIME_MS = 1_800_000L

/** A canonical ID made of [n] in hex, zero padded: a larger [n] sorts later, as the snapshot's order needs. */
internal fun idOf(n: Int): CanonicalId = CanonicalId.parse(n.toString(HEX_RADIX).padStart(ID_LENGTH, '0'))

/** The library every builder uses unless told otherwise. */
internal val LIB: CanonicalId = idOf(0xA0)

internal fun movie(
    id: CanonicalId,
    library: CanonicalId = LIB,
    rating: String? = null,
    genres: List<String> = emptyList(),
    tags: List<String> = emptyList(),
    year: Int? = null,
    deletedAtMs: Long? = null,
): RuleItem =
    RuleItem(
        item =
            ScheduleItem(
                id = id,
                kind = ScheduleItemKind.Movie,
                runtimeMs = RUNTIME_MS,
                createdAtMs = 0L,
                deletedAtMs = deletedAtMs,
                premiereDate = null,
                productionYear = year,
                seriesId = null,
                seasonNumber = null,
                episodeNumber = null,
            ),
        libraryId = library,
        seasonId = null,
        officialRating = rating,
        genres = genres,
        tags = tags,
    )

internal fun episode(
    id: CanonicalId,
    seriesId: CanonicalId?,
    seasonId: CanonicalId? = null,
    library: CanonicalId = LIB,
    rating: String? = null,
    genres: List<String> = emptyList(),
    tags: List<String> = emptyList(),
    year: Int? = null,
    deletedAtMs: Long? = null,
): RuleItem =
    RuleItem(
        item =
            ScheduleItem(
                id = id,
                kind = ScheduleItemKind.Episode,
                runtimeMs = RUNTIME_MS,
                createdAtMs = 0L,
                deletedAtMs = deletedAtMs,
                premiereDate = null,
                productionYear = year,
                seriesId = seriesId,
                seasonNumber = null,
                episodeNumber = null,
            ),
        libraryId = library,
        seasonId = seasonId,
        officialRating = rating,
        genres = genres,
        tags = tags,
    )

internal fun series(
    id: CanonicalId,
    rating: String? = null,
    genres: List<String> = emptyList(),
    tags: List<String> = emptyList(),
): RuleSeries = RuleSeries(id = id, officialRating = rating, genres = genres, tags = tags)

internal fun collection(
    id: CanonicalId,
    movies: Set<CanonicalId> = emptySet(),
    episodes: Set<CanonicalId> = emptySet(),
    series: Set<CanonicalId> = emptySet(),
    seasons: Set<CanonicalId> = emptySet(),
): RuleCollection = RuleCollection(id = id, movies = movies, episodes = episodes, series = series, seasons = seasons)

/** Items are given in any order here; the builder does not sort, because the evaluator must keep the order it is given. */
internal fun snapshot(
    items: List<RuleItem> = emptyList(),
    series: List<RuleSeries> = emptyList(),
    collections: List<RuleCollection> = emptyList(),
    libraries: Set<CanonicalId> = setOf(LIB),
    collectionsSynced: Boolean = true,
): RuleSnapshot =
    RuleSnapshot(
        cutoffMs = 0L,
        libraries = libraries,
        items = items,
        series = series.associateBy { it.id },
        collections = collections.associateBy { it.id },
        collectionsSynced = collectionsSynced,
    )

internal fun rules(
    sources: List<Source>,
    exclusions: List<Source>? = null,
    maxRating: String? = null,
): ChannelRules =
    ChannelRules(
        formatVersion = 1,
        name = null,
        seed = Seed(0uL),
        sources = sources,
        exclusions = exclusions,
        maxRating = maxRating,
        ordering = null,
        timing = Timing(null, null, null, null, null, null, BroadcastDay(0, null)),
        filler = null,
    )
