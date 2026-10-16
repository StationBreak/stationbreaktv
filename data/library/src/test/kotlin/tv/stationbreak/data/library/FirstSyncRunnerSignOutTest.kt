// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.StoredSession
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session

/**
 * Angua M1 (review of PR #250): the worker only verifies the session. If the user signs out while the server is still
 * answering `resume`, the worker must not bring the account back (no activation) or the session (no write).
 */
class FirstSyncRunnerSignOutTest {
    private val repository = TestLibraries.inMemory(signedIn = emptyList())

    @After
    fun close() = repository.close()

    private class GatedResume(
        val fake: FakeMediaServer,
        val gate: CompletableDeferred<Unit>,
    ) : MediaServer by fake {
        override suspend fun resume(stored: StoredSession): ServerResult<Session> {
            gate.await()
            return fake.resume(stored)
        }
    }

    @Test
    fun aSignOutWhileTheSessionIsBeingCheckedLeavesNothingBehind() =
        runBlocking {
            val fake = FakeMediaServer()
            val gate = CompletableDeferred<Unit>()
            val server = GatedResume(fake, gate)
            val store = InMemorySecretStore()
            val sessions = SessionRepository(store, server, repository)
            sessions.save(session)
            fake.resumeResult = ServerResult.Success(session)
            val settings = InMemoryServerSettings(ServerRecord(session.address), setOf(MOVIES))
            val runner = FirstSyncRunner(settings, sessions, server, repository, FakeClock())

            val running = async(start = CoroutineStart.UNDISPATCHED) { runner.outcome() }
            sessions.signOut(session)
            gate.complete(Unit)

            assertEquals(SyncOutcome.Cancelled, running.await())
            assertTrue(repository.dao.activeAccounts().isEmpty())
            assertEquals(RowCounts.NONE, repository.rowCounts())
            assertTrue(store.snapshot().isEmpty())
        }

    @Test
    fun anHttpToHttpsUpgradeIsNotWrittenBackByTheWorker() =
        runBlocking {
            val fake = FakeMediaServer()
            val store = InMemorySecretStore()
            val sessions = SessionRepository(store, fake, repository)
            sessions.save(session)
            val before = store.snapshot()
            val upgraded = session.copy(address = ServerAddress(Scheme.Https, "other.example.test", 443))
            fake.resumeResult = ServerResult.Success(upgraded)
            val settings = InMemoryServerSettings(ServerRecord(session.address), setOf(movies.id))
            val server = SyncWorld.server(movies to SyncWorld.source("m", 3))
            val runner = FirstSyncRunner(settings, sessions, PagedWith(server, fake), repository, FakeClock())

            runner.outcome()

            assertEquals(before, store.snapshot())
        }

    private class PagedWith(
        val paged: MediaServer,
        val fake: FakeMediaServer,
    ) : MediaServer by paged {
        override suspend fun resume(stored: StoredSession) = fake.resume(stored)
    }
}
