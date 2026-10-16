// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session

/**
 * Spec 06 task 3.4 (1.0 review notes): a page the server answers with a response-size error is asked for again at half
 * the limit, and a page that keeps failing to decode is halved down to the one item that will not decode, which is
 * skipped (decoding is strict by design, so a malformed item never takes the whole library down with it).
 */
class PageFallbackTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val server = SyncWorld.server(movies to SyncWorld.source("m", 300))

    @After
    fun close() = repository.close()

    private suspend fun run(
        pageSize: Int = 100,
        maxSkipped: Int = 3,
    ): SyncOutcome {
        repository.useAccount(ALICE)
        return SyncWorld.engine(repository, server, clock, pageSize, maxSkipped).run(session, ALICE, setOf(MOVIES))
    }

    private fun walk() = server.itemCalls.filter { it.changedSince == null }

    // The response-size error.

    @Test
    fun aPageTooBigForTheResponseLimitIsAskedForAgainAtHalfTheLimit() =
        runBlocking {
            server.maxItemsPerResponse = 40

            val outcome = run(pageSize = 100)

            assertEquals(SyncOutcome.Completed, outcome)
            // 100 failed, 50 failed, 25 worked, and the pages after it stay at 25.
            assertEquals(listOf(100, 50, 25, 25, 25), walk().take(5).map { it.limit })
            assertEquals(listOf(0, 0, 0, 25, 50), walk().take(5).map { it.startIndex })
            assertEquals(300, repository.stored(ALICE).size)
        }

    @Test
    fun theSmallerLimitIsKeptForTheRestOfTheLibrary() =
        runBlocking {
            server.maxItemsPerResponse = 100
            run(pageSize = 200)

            val limits = walk().map { it.limit }
            assertEquals(listOf(200, 100), limits.take(2))
            assertTrue(limits.drop(1).all { it == 100 })
        }

    @Test
    fun aServerErrorEvenForOneItemEndsTheSyncAsAServerFailure() =
        runBlocking {
            server.maxItemsPerResponse = 0

            val outcome = run(pageSize = 4)

            assertEquals(SyncOutcome.Failed(SyncFailure.Server), outcome)
            assertEquals(listOf(4, 2, 1), walk().map { it.limit })
            assertEquals(SyncState.LastSyncFailed(SyncFailure.Server), repository.activeStatus().first()?.state)
        }

    // The strict decode.

    @Test
    fun aMalformedItemIsFoundByHalvingThePageAndSkipped() =
        runBlocking {
            val bad = SyncWorld.item("m", 137).id
            server.malformed = setOf(bad)

            val outcome = run(pageSize = 100)

            assertEquals(SyncOutcome.Completed, outcome)
            val stored = repository.stored(ALICE).map { it.item.id }
            assertEquals(299, stored.size)
            assertTrue(bad !in stored)
            // The second page (100 to 199) was halved until it was the one item.
            val second = walk().filter { it.startIndex in 100..199 }
            assertEquals(1, second.last { it.startIndex == 137 }.limit)
            assertEquals(SyncState.Idle, repository.activeStatus().first()?.state)
        }

    @Test
    fun theCheckpointMovesPastASkippedItem() =
        runBlocking {
            server.malformed = setOf(SyncWorld.item("m", 0).id)

            run(pageSize = 100)

            // Item 0 of the library is the bad one: the walk went on from index 1 and finished.
            assertEquals(299, repository.stored(ALICE).size)
            assertEquals(300, repository.checkpoint(ALICE, MOVIES)?.nextStartIndex)
        }

    @Test
    fun aLibraryWhereEverythingFailsToDecodeStopsAfterTheSkipLimit() =
        runBlocking {
            server.malformed = (0 until 300).map { SyncWorld.item("m", it).id }.toSet()

            val outcome = run(pageSize = 100, maxSkipped = 3)

            assertEquals(SyncOutcome.Failed(SyncFailure.Server), outcome)
            assertEquals(0, repository.stored(ALICE).size)
            assertEquals(SyncState.LastSyncFailed(SyncFailure.Server), repository.activeStatus().first()?.state)
        }

    @Test
    fun nothingElseIsSkipped() =
        runBlocking {
            server.malformed = setOf(SyncWorld.item("m", 5).id, SyncWorld.item("m", 250).id)

            run(pageSize = 100)

            val missing = (0 until 300).map { SyncWorld.item("m", it).id } - repository.stored(ALICE).map { it.item.id }.toSet()
            assertEquals(listOf(ItemId("m-00005"), ItemId("m-00250")), missing)
        }

    // Other failures stop the sync and keep what was saved.

    @Test
    fun aRejectedSessionStopsTheSyncWithoutLooping() =
        runBlocking {
            server.respond = { if (it.startIndex == 100 && !it.idsOnly) MediaServerError.SessionExpired() else null }

            val outcome = run()

            assertEquals(SyncOutcome.Failed(SyncFailure.SessionExpired), outcome)
            assertEquals(100, repository.stored(ALICE).size)
            assertEquals(SyncState.LastSyncFailed(SyncFailure.SessionExpired), repository.activeStatus().first()?.state)
        }

    @Test
    fun aNetworkFailureIsReportedAsNetworkAndKeepsTheSavedPages() =
        runBlocking {
            server.respond = { if (it.startIndex == 200 && !it.idsOnly) MediaServerError.Timeout() else null }

            val outcome = run()

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), outcome)
            assertEquals(200, repository.stored(ALICE).size)
            assertEquals(200, repository.checkpoint(ALICE, MOVIES)?.nextStartIndex)
            assertEquals(setOf(MOVIES), repository.pendingLibraries(ALICE))
        }

    @Test
    fun theLibraryListingFailingIsAFailureToo() =
        runBlocking {
            server.librariesFailure = MediaServerError.ConnectionRefused()

            val outcome = run()

            assertEquals(SyncOutcome.Failed(SyncFailure.Network), outcome)
            assertEquals(0, repository.stored(ALICE).size)
        }
}
