// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import kotlin.time.Duration.Companion.minutes

/** The re-id read over the real tables (spec 26, Task 3.3): tombstones are the gone side, chosen libraries the live. */
class RoomSuccessorsTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)

    @After
    fun close() = repository.close()

    private fun canon(n: Int) = CanonicalId.parse(hexId(n))

    private fun film(
        n: Int,
        library: ItemId = MOVIES,
        ids: Map<String, String> = emptyMap(),
        name: String = "Invented film $n",
        year: Int? = null,
    ) = sitem(n, library = library).copy(name = name, providerIds = ids, productionYear = year)

    private fun episode(
        n: Int,
        series: Int,
        season: Int = 1,
        number: Int = 1,
    ) = MediaItem(
        id = ItemId(hexId(n)),
        name = "Invented episode $n",
        kind = ItemKind.Episode,
        runtime = 30.minutes,
        parentId = SHOWS,
        dateCreated = EPOCH - 1.minutes,
        seriesId = ItemId(hexId(series)),
        seasonNumber = season,
        episodeNumber = number,
    )

    private fun show(
        n: Int,
        name: String,
        tvdb: String,
    ) = series(hexId(n), name = name).copy(providerIds = mapOf("tvdb" to tvdb))

    private fun dashed(n: Int) =
        hexId(n)
            .let {
                listOf(it.take(8), it.substring(8, 12), it.substring(12, 16), it.substring(16, 20), it.substring(20))
            }.joinToString("-")

    private suspend fun choose(vararg libraries: String) =
        repository.applySelection(ALICE, libraries.mapTo(HashSet()) { ItemId(it) }, nowMs = 0)

    private suspend fun successors(
        vararg gone: Int,
        libraries: Set<ItemId> = setOf(MOVIES, SHOWS),
    ) = repository.successors(ALICE, libraries, gone.mapTo(HashSet()) { canon(it) })

    @Test
    fun aFilmThatCameBackWithTheSameProviderId_isFound() =
        runBlocking {
            choose(MOVIES.value, SHOWS.value)
            repository.put(ALICE, MOVIES, film(1, ids = mapOf("tmdb" to "5")))
            repository.tombstone(ALICE, listOf(ItemId(hexId(1))))
            repository.put(ALICE, MOVIES, film(2, ids = mapOf("tmdb" to "5")))

            assertEquals(mapOf(canon(1) to canon(2)), successors(1))
        }

    @Test
    fun aGoneIdWithNoRow_orStillLive_isAbsent() =
        runBlocking {
            choose(MOVIES.value, SHOWS.value)
            repository.put(ALICE, MOVIES, film(1, ids = mapOf("tmdb" to "5")), film(2, ids = mapOf("tmdb" to "5")))

            assertEquals(emptyMap<CanonicalId, CanonicalId>(), successors(1, 2, 3))
        }

    @Test
    fun anEpisodeWhoseSeriesCameBackUnderANewId_isFoundThroughTheTombstonedSeries() =
        runBlocking {
            choose(MOVIES.value, SHOWS.value)
            repository.putSeries(ALICE, SHOWS, show(100, "Invented Show", tvdb = "9"))
            repository.put(ALICE, SHOWS, episode(1, series = 100))
            repository.tombstone(ALICE, listOf(ItemId(hexId(1))))
            repository.tombstoneSeries(ALICE, listOf(ItemId(hexId(100))))
            repository.putSeries(ALICE, SHOWS, show(200, "Other Name", tvdb = "9"))
            repository.put(ALICE, SHOWS, episode(2, series = 200))

            assertEquals(mapOf(canon(1) to canon(2)), successors(1))
        }

    @Test
    fun candidateInUnchosenLibrary_noMatch() =
        runBlocking {
            choose(MOVIES.value, SHOWS.value)
            repository.put(ALICE, MOVIES, film(1, ids = mapOf("tmdb" to "5")))
            repository.tombstone(ALICE, listOf(ItemId(hexId(1))))
            repository.put(ALICE, SHOWS, film(2, library = SHOWS, ids = mapOf("tmdb" to "5")))

            assertEquals(emptyMap<CanonicalId, CanonicalId>(), successors(1, libraries = setOf(MOVIES)))
            assertEquals(mapOf(canon(1) to canon(2)), successors(1, libraries = setOf(MOVIES, SHOWS)))
        }

    @Test
    fun aTombstoneIsNeverACandidate() =
        runBlocking {
            choose(MOVIES.value, SHOWS.value)
            repository.put(ALICE, MOVIES, film(1, ids = mapOf("tmdb" to "5")), film(2, ids = mapOf("tmdb" to "5")))
            repository.tombstone(ALICE, listOf(ItemId(hexId(1)), ItemId(hexId(2))))

            assertEquals(emptyMap<CanonicalId, CanonicalId>(), successors(1, 2))
        }

    @Test
    fun anotherAccountsRows_areNotRead() =
        runBlocking {
            repository.applySelection(BOB, setOf(MOVIES), nowMs = 0)
            choose(MOVIES.value)
            repository.put(ALICE, MOVIES, film(1, ids = mapOf("tmdb" to "5")))
            repository.tombstone(ALICE, listOf(ItemId(hexId(1))))
            repository.put(BOB, MOVIES, film(2, ids = mapOf("tmdb" to "5")))

            assertEquals(emptyMap<CanonicalId, CanonicalId>(), successors(1))
        }

    @Test
    fun aGoneItemStoredWithADashedUpperCaseId_isFoundByItsCanonicalId() =
        runBlocking {
            choose(MOVIES.value)
            val raw = ItemId(dashed(1).uppercase())
            repository.put(ALICE, MOVIES, film(1, ids = mapOf("tmdb" to "5")).copy(id = raw))
            repository.tombstone(ALICE, listOf(raw))
            repository.put(ALICE, MOVIES, film(2, ids = mapOf("tmdb" to "5")))

            assertEquals(mapOf(canon(1) to canon(2)), successors(1))
        }

    @Test
    fun moreGoneIdsAndLibrariesThanSqliteBinds_areReadInChunks() =
        runBlocking {
            choose(MOVIES.value)
            val count = LibraryDao.MAX_VARIABLES * 2 + 7
            repository.put(ALICE, MOVIES, *(1..count).map { film(it, ids = mapOf("tmdb" to "$it")) }.toTypedArray())
            repository.tombstone(ALICE, (1..count).map { ItemId(hexId(it)) })
            val back = (1..count).map { film(10_000 + it, ids = mapOf("tmdb" to "$it")) }
            repository.put(ALICE, MOVIES, *back.toTypedArray())
            val others = (0 until LibraryDao.MAX_VARIABLES + 5).map { ItemId(hexId(0xC000 + it)) }
            val manyLibraries = others.toSet() + MOVIES

            val found = repository.successors(ALICE, manyLibraries, (1..count).mapTo(HashSet()) { canon(it) })

            assertEquals(count, found.size)
            assertEquals(canon(10_000 + 123), found[canon(123)])
        }
}
