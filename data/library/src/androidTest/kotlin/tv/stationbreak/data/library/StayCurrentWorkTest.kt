// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.ListLibrarySource
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Spec 06 task 4.0, review round (Carrot M5 and M6): the stay-current work against the real WorkManager (the test
 * driver, on the API 25 emulator) and the real Room: two opens give the expected states, the periodic job is unique and
 * keeps the existing one, and [StayCurrentWorker.doWork] round-trips its flags, fails with no provider, always reports
 * success, and leaves the recorded moment alone when cancelled. Generated placeholder data only.
 */
@RunWith(AndroidJUnit4::class)
class StayCurrentWorkTest {
    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val account = LibraryAccount(ServerId("work-test-server"), UserId("work-test-user"))
    private val movies = Library(ItemId("work-test-movies"), "Movies", LibraryKind.Movies)
    private val address = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)
    private val session =
        Session(
            account.user,
            AccessToken("placeholder-token-for-the-work-test"),
            ServerInfo("Placeholder", account.server, ServerVersion(12, 2, 0)),
            address,
            null,
            false,
        )
    private val created = Instant.fromEpochMilliseconds(1_600_000_000_000)
    private val items =
        (0 until 30).map {
            MediaItem(ItemId("w-$it"), "Item $it", ItemKind.Movie, 90.minutes, movies.id, dateCreated = created)
        }
    private val source = ListLibrarySource(items.toMutableList())
    private val fake = FakeMediaServer()
    private val server = PagedMediaServer(listOf(movies), mapOf(movies.id to source), fake)
    private lateinit var repository: RoomLibraryRepository
    private lateinit var stayCurrent: StayCurrent
    private lateinit var enqueuer: WorkManagerEnqueuer

    @Before
    fun open() {
        context().deleteDatabase(RoomLibraryRepository.DATABASE_NAME)
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(appContext, config)
        repository = RoomLibraryRepository.create(appContext)
        val sessions = SessionRepository(InMemorySecretStore(), server, repository)
        val settings = InMemoryServerSettings(ServerRecord(address), setOf(movies.id))
        val runner = StayCurrentRunner(settings, sessions, server, repository, Clock.System)
        enqueuer = WorkManagerEnqueuer(appContext)
        stayCurrent = StayCurrent(enqueuer, enqueuer, repository, SyncTriggerPolicy(Clock.System), runner)
        runBlocking {
            sessions.save(session)
            fake.resumeResult = ServerResult.Success(session)
            // The first sync, through the real engine, so the account is past it.
            FirstSyncEngine(repository, server, Clock.System).run(session, account, setOf(movies.id))
        }
        server.calls.clear()
        provider = Provider(appContext, stayCurrent)
    }

    @After
    fun close() {
        repository.close()
        context().deleteDatabase(RoomLibraryRepository.DATABASE_NAME)
    }

    private fun context(): Context = appContext

    private lateinit var provider: Provider

    /** An `Application` stand-in that supplies the worker its runner, as `StationBreakApp` does. */
    private class Provider(
        base: Context,
        private val current: StayCurrent,
    ) : ContextWrapper(base),
        StayCurrentProvider {
        override fun stayCurrent(): StayCurrent = current

        override fun getApplicationContext(): Context = this
    }

    private fun worker(
        incremental: Boolean,
        reconcile: Boolean,
        context: Context = provider,
    ): StayCurrentWorker =
        TestListenableWorkerBuilder<StayCurrentWorker>(context)
            .setInputData(
                Data
                    .Builder()
                    .putBoolean(StayCurrentWork.KEY_INCREMENTAL, incremental)
                    .putBoolean(StayCurrentWork.KEY_RECONCILE, reconcile)
                    .build(),
            ).build()

    private fun infos(name: String): List<WorkInfo> {
        val manager = WorkManager.getInstance(appContext)
        return manager.getWorkInfosForUniqueWork(name).get()
    }

    // The work in the real WorkManager.

    @Test
    fun threeOpensWhileOfflineLeaveExactlyOneQueuedWork() =
        runBlocking {
            stayCurrent.onOpen()
            stayCurrent.onOpen()
            stayCurrent.onOpen()

            val states = infos(StayCurrentWork.OPEN_NAME).map { it.state }
            // The network constraint is unmet, so the first waits; the others find it there and add nothing.
            assertEquals(listOf(WorkInfo.State.ENQUEUED), states)
        }

    @Test
    fun thePeriodicJobIsUniqueAndTheSecondScheduleKeepsTheFirst() {
        stayCurrent.schedulePeriodic()
        val first = infos(StayCurrentWork.PERIODIC_NAME).map { it.id }
        stayCurrent.schedulePeriodic()
        val second = infos(StayCurrentWork.PERIODIC_NAME).map { it.id }

        assertEquals(1, first.size)
        assertEquals(first, second)
    }

    // The worker.

    @Test
    fun theFlagsRoundTripAndPickThePasses() =
        runBlocking {
            worker(incremental = true, reconcile = false).doWork()
            assertTrue(server.calls.isNotEmpty() && server.calls.none { it.idsOnly })

            server.calls.clear()
            worker(incremental = false, reconcile = true).doWork()
            assertTrue(server.calls.isNotEmpty() && server.calls.all { it.idsOnly })

            server.calls.clear()
            worker(incremental = false, reconcile = false).doWork()
            assertTrue(server.calls.isEmpty())
        }

    @Test
    fun anOpenWorkerWithNoFlagsPicksItsPlanWhenItRuns() =
        runBlocking {
            val noFlags = TestListenableWorkerBuilder<StayCurrentWorker>(provider).build()

            noFlags.doWork()

            // A fresh first sync: only the reconciliation is due.
            assertTrue(server.calls.isNotEmpty() && server.calls.all { it.idsOnly })
        }

    @Test
    fun withNoProviderTheWorkerFailsAtOnce() =
        runBlocking {
            val result = worker(incremental = true, reconcile = true, context = appContext).doWork()

            assertEquals(ListenableWorker.Result.failure(), result)
            assertTrue(server.calls.isEmpty())
        }

    @Test
    fun aFailingServerStillReportsSuccessAndTheFailureIsRecorded() =
        runBlocking {
            server.respond = { MediaServerError.Timeout() }

            val result = worker(incremental = true, reconcile = true).doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            val status = repository.activeStatus().first()!!
            assertEquals(SyncState.LastSyncFailed(SyncFailure.Network), status.state)
        }

    @Test
    fun aCancelledWorkerLeavesTheRecordedMomentAloneAndIsNotRunning() =
        runBlocking {
            val before = repository.activeStatus().first()
            lateinit var job: Job
            server.respond = { call ->
                if (call.number == 1) job.cancel()
                null
            }
            val runner = provider.stayCurrent().runner

            job = launch { worker(incremental = true, reconcile = true).doWork() }
            job.join()

            assertTrue(job.isCancelled)
            assertEquals(before, repository.activeStatus().first())
            assertFalse(runner.isRunning)
        }
}
