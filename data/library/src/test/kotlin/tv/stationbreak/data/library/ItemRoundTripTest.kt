// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Spec 06 task 2.7: every field of an item survives a write and a read (FR: stored fields). */
class ItemRoundTripTest {
    private val repository = TestLibraries.inMemory()

    @After
    fun close() = repository.close()

    private val everyField =
        MediaItem(
            id = ItemId("item-full"),
            name = "Ünïcode name with 'quotes' and \"doubles\", 日本語",
            kind = ItemKind.Episode,
            runtime = 47.minutes + 123.milliseconds,
            parentId = ItemId("season-1"),
            dateCreated = Instant.fromEpochMilliseconds(1_650_000_123_456),
            premiereDate = "1994-09-22",
            productionYear = 1994,
            officialRating = "TV-14",
            genres = listOf("Drama", "Comedy", "Drama again"),
            tags = listOf("holiday", "classic"),
            seriesId = ItemId("series-9"),
            seasonNumber = 3,
            episodeNumber = 12,
            studios = listOf("Invented Studio B", "Invented Studio A"),
        )

    @Test
    fun everyFieldOfAnItemSurvivesAWriteAndARead() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.writePage(ALICE, MOVIES, listOf(everyField), nextStartIndex = 1, totalCount = 1, run = 1)

            val read = repository.snapshot(ALICE, setOf(MOVIES), EPOCH)

            assertEquals(listOf(everyField), read)
        }

    @Test
    fun anItemWithOnlyTheRequiredFieldsReadsBackWithTheOptionalOnesEmpty() =
        runBlocking {
            val bare = MediaItem(ItemId("item-bare"), "Bare", ItemKind.Other, null, null)
            repository.select(ALICE, setOf(MOVIES))
            repository.writePage(ALICE, MOVIES, listOf(bare), nextStartIndex = 1, totalCount = 1, run = 1)

            val stored = repository.stored(ALICE).single()

            assertEquals(bare, stored.item)
            assertEquals(MOVIES, stored.library)
            assertEquals(null, stored.deletedAt)
        }

    @Test
    fun theOrderOfGenresTagsAndStudiosIsKept() =
        runBlocking {
            val ordered =
                everyField.copy(
                    genres = listOf("Z", "A", "M"),
                    tags = listOf("2", "1", "3"),
                    studios = listOf("Zed", "Alpha", "Mid"),
                )
            repository.select(ALICE, setOf(MOVIES))
            repository.writePage(ALICE, MOVIES, listOf(ordered), nextStartIndex = 1, totalCount = 1, run = 1)

            assertEquals(ordered, repository.stored(ALICE).single().item)
        }

    @Test
    fun everyItemKindRoundTrips() =
        runBlocking {
            val items = ItemKind.entries.map { MediaItem(ItemId("kind-${it.name}"), it.name, it, 1.minutes, null) }
            repository.select(ALICE, setOf(MOVIES))
            repository.writePage(ALICE, MOVIES, items, nextStartIndex = items.size, totalCount = items.size, run = 1)

            assertEquals(items.map { it.kind }.toSet(), repository.stored(ALICE).map { it.item.kind }.toSet())
        }

    @Test
    fun runtimeAndCreatedAtKeepEveryMillisecondAtTheExtremes() =
        runBlocking {
            val runtimes = listOf(1L, 999L, 1_000L, Long.MAX_VALUE / 10_000L)
            val created = listOf(Long.MIN_VALUE / 10_000L, -1L, 0L, 4_503_599_627_370_496L)
            val items =
                runtimes.indices.map { index ->
                    val moment = Instant.fromEpochMilliseconds(created[index])
                    item("edge-$index", created = moment, runtime = runtimes[index].milliseconds)
                }
            repository.select(ALICE, setOf(MOVIES))
            repository.writePage(ALICE, MOVIES, items, nextStartIndex = items.size, totalCount = items.size, run = 1)

            val read = repository.stored(ALICE).map { it.item }.sortedBy { it.id.value }

            assertEquals(runtimes, read.map { it.runtime?.inWholeMilliseconds })
            assertEquals(created, read.map { it.dateCreated?.toEpochMilliseconds() })
        }

    @Test
    fun anItemWithNoPremiereDateReadsBackWithNone() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            val bare = listOf(item("no-premiere"))
            repository.writePage(ALICE, MOVIES, bare, nextStartIndex = 1, totalCount = 1, run = 1)

            assertEquals(
                null,
                repository
                    .stored(ALICE)
                    .single()
                    .item.premiereDate,
            )
        }
}
