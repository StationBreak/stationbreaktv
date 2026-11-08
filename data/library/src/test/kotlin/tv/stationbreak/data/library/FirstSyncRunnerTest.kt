// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.selection.ResumeGate
import tv.stationbreak.core.model.StoredSession
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session

/**
 * The first-sync worker's run (spec 26, Part 4 §2 "Where each worker waits"): it waits for the selection's resume at
 * start before it reads anything, so a run the system starts early never reads a choice the resume is still putting
 * in line. Invented libraries and names only.
 */
class FirstSyncRunnerTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = emptyList())

    @After
    fun close() = repository.close()

    // The settings, with their reads counted.
    private class CountedSettings(
        val inner: InMemoryServerSettings,
    ) : ServerSettings by inner {
        var loads = 0

        override suspend fun load(): ServerRecord? {
            loads++
            return inner.load()
        }
    }

    @Test
    fun outcome_waitsForTheResume_beforeItReadsTheSettings() =
        runBlocking {
            val fake = FakeMediaServer()
            val sessions = SessionRepository(InMemorySecretStore(), fake, repository)
            sessions.save(session)
            fake.resumeResult = ServerResult.Success(session)
            val settings = CountedSettings(InMemoryServerSettings(ServerRecord(session.address), setOf(MOVIES)))
            val server = SyncWorld.server(movies to SyncWorld.source("m", 3))
            val gate = ResumeGate()
            val runner = firstSyncRunner(settings, sessions, PagedWith(server, fake), repository, clock, gate)

            val running = async(start = CoroutineStart.UNDISPATCHED) { runner.outcome() }

            assertFalse("still waiting for the resume", running.isCompleted)
            assertEquals("nothing read before the resume", 0, settings.loads)
            gate.open()
            assertEquals(SyncOutcome.Completed, running.await())
            assertEquals(1, settings.loads)
        }

    private class PagedWith(
        val paged: MediaServer,
        val fake: FakeMediaServer,
    ) : MediaServer by paged {
        override suspend fun resume(stored: StoredSession) = fake.resume(stored)
    }
}
