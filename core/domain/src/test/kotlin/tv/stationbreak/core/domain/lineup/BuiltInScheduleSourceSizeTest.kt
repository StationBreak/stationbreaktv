// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.testing.SyntheticLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A size guard: the whole starter catalog planned against a 15,000-item synthetic library, then the switched-on
 * channels' days worked out at once (one snapshot read, every pool, the leftovers). It guards against an accidental
 * per-item loop over the channels; it is not a benchmark.
 */
class BuiltInScheduleSourceSizeTest {
    private val synthetic = SyntheticLibrary(ITEMS, libraryId = ItemId(LIBRARY_ID.value))

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
            libraryId = LIBRARY_ID,
            seasonId = null,
            officialRating = media.officialRating,
            genres = media.genres,
            tags = media.tags,
        )
    }

    // A series takes its first episode's rating, genres and tags.
    private fun seriesOf(
        id: CanonicalId,
        first: MediaItem,
    ) = RuleSeries(id, first.officialRating, first.genres, first.tags)

    @Test
    fun theFullLineupAgainstFifteenThousandItemsIsFast() =
        runBlocking<Unit> {
            val library = CountingLibrary()
            // Movies and episodes with a runtime the scheduler takes; a series takes its first episode's fields.
            val media =
                synthetic.items
                    .filter { it.kind == ItemKind.Movie || it.kind == ItemKind.Episode }
                    .filter { (it.runtime?.inWholeMilliseconds ?: 0L) > 0L }
                    .toList()
            library.seed(media.map(::ruleItemOf))
            val episodes = media.filter { it.seriesId != null }
            val bySeries = episodes.groupBy { CanonicalId.parse(checkNotNull(it.seriesId).value) }
            val series = bySeries.map { (id, its) -> seriesOf(id, its.first()) }
            library.delegate.seedSeries(ACCOUNT, *series.toTypedArray())
            library.delegate.setCollectionsSynced(ACCOUNT, true)
            val clock =
                object : Clock {
                    override fun now(): Instant = NOON
                }
            val service = LineupService(RecordingStore(), library, clock, { 0 }, {}, StarterCatalog)
            service.refresh(ACCOUNT, LIBRARIES)
            library.snapshotCutoffs.clear()
            library.delegate.useAccount(ACCOUNT)
            val source = BuiltInScheduleSource(service, library) { LIBRARIES }

            val began = System.nanoTime()
            val airings = source.airings(NOON)
            val elapsedMs = (System.nanoTime() - began) / NANOS_PER_MS

            println(
                "BuiltInScheduleSourceSizeTest: ${media.size} items, ${airings.size} channels on, " +
                    "${airings.count { it.hidden != null }} hidden, ${library.snapshotCutoffs.size} snapshot read, " +
                    "airings in $elapsedMs ms",
            )
            assertTrue(airings.size > MANY_ON, "the synthetic library fills many channels")
            assertEquals(1, library.snapshotCutoffs.size)
            assertTrue(elapsedMs < LIMIT_MS, "took $elapsedMs ms, limit $LIMIT_MS ms")
        }

    private companion object {
        const val ITEMS = 15_000
        const val NANOS_PER_MS = 1_000_000L
        const val LIMIT_MS = 5_000L
        const val MANY_ON = 10
    }
}
