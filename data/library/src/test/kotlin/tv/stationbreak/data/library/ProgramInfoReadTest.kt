// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.SeriesInfo
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import kotlin.time.Duration.Companion.minutes

/** Spec 14 task 1: the names of scheduled items, so the live screen's banner can say what is on. */
class ProgramInfoReadTest {
    private val repository = TestLibraries.inMemory(FakeClock())

    @After
    fun close() = repository.close()

    private val seriesId = ItemId(hexId(0x5001))
    private val episodeId = ItemId(hexId(0x1001))
    private val movieId = ItemId(hexId(0x1002))

    private suspend fun save(account: LibraryAccount) {
        repository.select(account, setOf(SHOWS))
        val episode =
            MediaItem(
                episodeId,
                "Episode One",
                ItemKind.Episode,
                30.minutes,
                SHOWS,
                dateCreated = EPOCH - 1.minutes,
                seriesId = seriesId,
                seasonNumber = 2,
                episodeNumber = 5,
            )
        val movie =
            MediaItem(movieId, "Test Movie A", ItemKind.Movie, 90.minutes, SHOWS, dateCreated = EPOCH - 1.minutes)
        val series = SeriesInfo(seriesId, "Test Series B", null, emptyList(), emptyList())
        repository.writePage(account, SHOWS, listOf(episode, movie), 2, 2, run = 1, series = listOf(series))
    }

    @Test
    fun programInfoReturnsTitleAndSeriesNameForEpisodes() =
        runBlocking {
            save(ALICE)

            val info = repository.programInfo(ALICE, setOf(canon(0x1001), canon(0x1002)))

            assertEquals(
                mapOf(
                    canon(0x1001) to
                        ProgramInfo(canon(0x1001), ScheduleItemKind.Episode, "Episode One", "Test Series B", 2, 5),
                    canon(0x1002) to
                        ProgramInfo(canon(0x1002), ScheduleItemKind.Movie, "Test Movie A", null, null, null),
                ),
                info,
            )
        }

    @Test
    fun programInfoOmitsDeletedItems() =
        runBlocking {
            save(ALICE)
            repository.tombstone(ALICE, listOf(episodeId))

            val info = repository.programInfo(ALICE, setOf(canon(0x1001), canon(0x1002)))

            assertEquals(setOf(canon(0x1002)), info.keys)
        }

    @Test
    fun programInfoIsScopedToTheAccount() =
        runBlocking {
            save(ALICE)

            assertTrue(repository.programInfo(BOB, setOf(canon(0x1001), canon(0x1002))).isEmpty())
        }

    @Test
    fun programInfoOfAnEmptySetIsEmpty() =
        runBlocking {
            save(ALICE)

            assertTrue(repository.programInfo(ALICE, emptySet()).isEmpty())
        }

    @Test
    fun programInfoReadsMoreIdsThanOneStatementHolds() =
        runBlocking {
            save(ALICE)
            val many = (0x2000 until 0x2000 + 1_000).map { canon(it) }.toSet() + canon(0x1001)

            assertEquals(setOf(canon(0x1001)), repository.programInfo(ALICE, many).keys)
        }
}
