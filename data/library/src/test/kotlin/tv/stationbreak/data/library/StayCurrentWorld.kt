// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import kotlin.time.Clock
import kotlin.time.TimeSource

/** Builders for the stay-current tests (spec 06 task 4.0), beside [SyncWorld]. */
internal object StayCurrentWorld {
    fun engine(
        repository: RoomLibraryRepository,
        server: PagedMediaServer,
        clock: Clock,
        pageSize: Int = 100,
        unfindable: UnfindableIds = UnfindableIds(),
    ) = StayCurrentEngine(repository, server, clock, pageSize = pageSize, unfindable = unfindable)
}

/** A [WorkEnqueuer] and [PeriodicEnqueuer] that record what they were asked and do nothing else. */
internal class RecordingEnqueuer :
    WorkEnqueuer,
    PeriodicEnqueuer {
    val enqueued = mutableListOf<Triple<String, ExistingWorkPolicy, OneTimeWorkRequest>>()
    val periodic = mutableListOf<Triple<String, ExistingPeriodicWorkPolicy, PeriodicWorkRequest>>()
    val cancelled = mutableListOf<String>()

    override fun enqueueUnique(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        enqueued += Triple(name, policy, request)
    }

    override fun enqueueUniquePeriodic(
        name: String,
        policy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    ) {
        periodic += Triple(name, policy, request)
    }

    override fun cancelUnique(name: String) {
        cancelled += name
    }
}

/**
 * One signed-in account with a 300-item library, and the stay-current runner over the same fake server: the world the
 * quiet and offline tests start from. [firstSyncDone] runs the first sync to its end.
 */
internal class StayCurrentRig(
    val clock: FakeClock = FakeClock(),
    timeSource: TimeSource = TimeSource.Monotonic,
    signedIn: List<LibraryAccount> = TestLibraries.STANDARD_ACCOUNTS,
    report: (String) -> Unit = {},
) {
    val repository = TestLibraries.inMemory(clock, signedIn = signedIn)
    val fake = FakeMediaServer()
    val library = SyncWorld.source("m", 300)
    val server = PagedMediaServer(listOf(SyncWorld.movies), mapOf(MOVIES to library), fake)
    val store = InMemorySecretStore()
    val sessions = SessionRepository(store, server, repository)
    val settings = InMemoryServerSettings(ServerRecord(SyncWorld.session.address), setOf(MOVIES))
    val runner = StayCurrentRunner(settings, sessions, server, repository, clock, timeSource, report)

    /** Signs in and runs the first sync to its end; the recorded moment is the clock at that time. */
    suspend fun firstSyncDone() {
        sessions.save(SyncWorld.session)
        fake.resumeResult = ServerResult.Success(SyncWorld.session)
        val verdict = FirstSyncRunner(settings, sessions, server, repository, clock).run(attempt = 0)
        check(verdict == SyncVerdict.Success) { "the first sync should finish: $verdict" }
        server.calls.clear()
    }

    fun close() = repository.close()
}
