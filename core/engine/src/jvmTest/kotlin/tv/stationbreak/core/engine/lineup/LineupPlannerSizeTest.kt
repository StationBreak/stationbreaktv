// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.testing.SyntheticLibrary
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A size guard: the whole starter catalog planned against a 15,000-item synthetic library, twice (first setup, then a
 * refresh). Every recipe's pool is evaluated once per call, so this is about a hundred single passes. It guards against
 * an accidental per-item loop over the recipes, not a benchmark.
 */
class LineupPlannerSizeTest {
    private val server = CanonicalId.parse("22222222222222222222222222222222")
    private val user = CanonicalId.parse("33333333333333333333333333333333")

    private fun snapshotOf(library: SyntheticLibrary): RuleSnapshot {
        val media = library.items.filter { it.kind == ItemKind.Movie || it.kind == ItemKind.Episode }.toList()
        val items = media.map(::ruleItemOf)
        // A series takes its genres and rating from its first episode.
        val series =
            media
                .filter { it.seriesId != null }
                .groupBy { CanonicalId.parse(checkNotNull(it.seriesId).value) }
                .mapValues { (id, episodes) ->
                    RuleSeries(id, episodes.first().officialRating, episodes.first().genres, episodes.first().tags)
                }
        return RuleSnapshot(
            cutoffMs = Long.MAX_VALUE,
            libraries = setOf(CanonicalId.parse(library.libraryId.value)),
            items = items,
            series = series,
            collections = emptyMap(),
            collectionsSynced = true,
        )
    }

    private fun ruleItemOf(media: MediaItem): RuleItem {
        val episode = media.kind == ItemKind.Episode
        return RuleItem(
            item =
                ScheduleItem(
                    id = CanonicalId.parse(media.id.value),
                    kind = if (episode) ScheduleItemKind.Episode else ScheduleItemKind.Movie,
                    runtimeMs = media.runtime?.inWholeMilliseconds ?: 0L,
                    createdAtMs = checkNotNull(media.dateCreated).toEpochMilliseconds(),
                    deletedAtMs = null,
                    premiereDate = null,
                    productionYear = media.productionYear,
                    seriesId = media.seriesId?.let { CanonicalId.parse(it.value) },
                    seasonNumber = media.seasonNumber,
                    episodeNumber = media.episodeNumber,
                ),
            libraryId = CanonicalId.parse(checkNotNull(media.parentId).value),
            seasonId = null,
            officialRating = media.officialRating,
            genres = media.genres,
            tags = media.tags,
        )
    }

    @Test
    fun theFullCatalogAgainstFifteenThousandItemsIsFast() {
        val snapshot = snapshotOf(SyntheticLibrary(ITEMS))
        val names = CollectionNames(emptyMap())
        val began = System.nanoTime()
        val first = LineupPlanner.plan(null, server, user, 0, snapshot, names, 1L)
        val refreshed = LineupPlanner.plan(first, server, user, 0, snapshot, names, 2L)
        val elapsedMs = (System.nanoTime() - began) / NANOS_PER_MS
        val on = refreshed.channels.count { it.state == ChannelState.On }
        println(
            "LineupPlannerSizeTest: ${StarterCatalog.recipes.size} recipes, ${snapshot.items.size} items, " +
                "$on on, finalised ${refreshed.finalised}, two plans in $elapsedMs ms",
        )
        assertTrue(first.finalised, "the synthetic library fills enough recipes to finalise")
        assertTrue(elapsedMs < LIMIT_MS, "took $elapsedMs ms, limit $LIMIT_MS ms")
    }

    private companion object {
        const val ITEMS = 15_000
        const val NANOS_PER_MS = 1_000_000L
        const val LIMIT_MS = 10_000L
    }
}
