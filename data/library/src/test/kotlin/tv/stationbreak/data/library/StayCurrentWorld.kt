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
import tv.stationbreak.core.domain.selection.HoldExpiry
import tv.stationbreak.core.domain.selection.SelectionReady
import tv.stationbreak.core.domain.selection.ServerListings
import tv.stationbreak.core.model.Library
import kotlin.time.Clock
import kotlin.time.TimeSource

/** The selection's listing port for a run whose test does not look at it: every listing is dropped. */
internal val NO_LISTINGS = ServerListings { _, _ -> }

/** The selection's hold-expiry port for a run whose test does not look at it: nothing expires. */
internal val NO_EXPIRY = HoldExpiry {}

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

    /** What a run asked of the selection after its passes, in order: `expire` and `follow` (spec 26, Task 4.5). */
    val asked = mutableListOf<String>()

    /** Each listing a run handed the selection to follow. */
    val followed = mutableListOf<ServerResult<List<Library>>>()

    /** Runs inside each hold expiry a run asks for. */
    var onExpire: suspend (LibraryAccount) -> Unit = {}

    /** Runs inside each listing a run hands over. */
    var onFollow: suspend (LibraryAccount, ServerResult<List<Library>>) -> Unit = { _, _ -> }

    private val holds =
        HoldExpiry { account ->
            asked += "expire"
            onExpire(account)
        }
    private val listings =
        ServerListings { account, listed ->
            asked += "follow"
            followed += listed
            onFollow(account, listed)
        }
    val runner =
        StayCurrentRunner(
            settings,
            sessions,
            server,
            repository,
            SelectionReady.Complete,
            listings,
            holds,
            clock,
            timeSource,
            report,
        )

    /** The stored selection made gone: the server lists none of its chosen libraries any more. */
    suspend fun goneSelection() {
        val stored = checkNotNull(settings.loadSelection())
        check(settings.saveSelection(stored.copy(seq = stored.seq + 1, gone = true)))
    }

    /** Signs in and runs the first sync to its end; the recorded moment is the clock at that time. */
    suspend fun firstSyncDone() {
        sessions.save(SyncWorld.session)
        fake.resumeResult = ServerResult.Success(SyncWorld.session)
        val verdict = firstSyncRunner(settings, sessions, server, repository, clock).run(attempt = 0)
        check(verdict == SyncVerdict.Success) { "the first sync should finish: $verdict" }
        server.calls.clear()
    }

    fun close() = repository.close()
}
