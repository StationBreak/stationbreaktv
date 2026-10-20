// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.StoredBytes
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class LineupRefresherTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }

    init {
        runBlocking { library.delegate.useAccount(ACCOUNT) }
    }

    private val store = RecordingStore()
    private val events = mutableListOf<LineupEvent>()
    private var offset = 0
    private var libraries: Set<ItemId>? = LIBRARIES
    private val job = Job()
    private val scope = CoroutineScope(job)

    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }

    private fun serviceOver(lib: LibraryRepository): LineupService {
        val report: (LineupEvent) -> Unit = { events += it }
        return LineupService(store, lib, clock, { offset }, report, TestCatalog)
    }

    private val service = serviceOver(library)

    private fun refresher() = LineupRefresher(service, library, { libraries }) { events += it }

    private val refreshes get() = library.snapshotCutoffs.size

    @AfterTest
    fun stop() {
        job.cancel()
    }

    private suspend fun until(
        what: String,
        condition: suspend () -> Boolean,
    ) {
        withTimeout(WAIT_MS) { while (!condition()) delay(10) }
        check(condition()) { what }
    }

    private suspend fun settle() = delay(SETTLE_MS)

    @Test
    fun refreshesAtStartWhenAbsent() =
        runBlocking<Unit> {
            refresher().start(scope)

            until("refreshed") { refreshes >= 1 }
            settle()
            assertEquals(1, refreshes)
            assertTrue(store.inner.peek(ACCOUNT) != null, "and saved")
        }

    @Test
    fun refreshesAtStartWhenCatalogBehind() =
        runBlocking<Unit> {
            // A newer catalog is taken up only once the collections are in.
            library.delegate.setCollectionsSynced(ACCOUNT, true)
            val behind = Lineup(SERVER, USER, 0, 0, finalised = true, channels = emptyList())
            store.inner.save(ACCOUNT, LineupCodec.encode(behind))

            refresher().start(scope)

            until("refreshed") { refreshes >= 1 }
            until("published") { service.loaded(ACCOUNT)?.lineup?.catalogVersion == 1 }
        }

    @Test
    fun refreshesAtStartWhenUnfinalised() =
        runBlocking<Unit> {
            val unfinalised = Lineup(SERVER, USER, 1, 0, finalised = false, channels = emptyList())
            store.inner.save(ACCOUNT, LineupCodec.encode(unfinalised))

            refresher().start(scope)

            until("refreshed") { refreshes >= 1 }
        }

    @Test
    fun doesNotRefreshAtStartWhenCurrent() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val before = refreshes

            refresher().start(scope)
            settle()

            assertEquals(before, refreshes)
        }

    @Test
    fun refreshesAfterCollectionPass() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val before = refreshes
            refresher().start(scope)
            settle()
            assertEquals(before, refreshes, "null is no trigger")

            val first = Instant.parse("2026-10-19T10:00:00Z")
            library.delegate.completeCollectionPass(ACCOUNT, first)
            until("first pass") { refreshes == before + 1 }

            library.delegate.completeCollectionPass(ACCOUNT, first)
            settle()
            assertEquals(before + 1, refreshes, "the same value again is no trigger")

            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T11:00:00Z"))
            until("second pass") { refreshes == before + 2 }
        }

    @Test
    fun refreshesWhenLastSuccessAdvances() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val before = refreshes
            refresher().start(scope)
            settle()

            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.Idle, Instant.parse("2026-10-19T10:00:00Z")))
            until("first success") { refreshes == before + 1 }

            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.Idle, Instant.parse("2026-10-19T11:00:00Z")))
            until("second success") { refreshes == before + 2 }
        }

    @Test
    fun doesNotRefreshOnFailureOrRunning() =
        runBlocking<Unit> {
            val success = Instant.parse("2026-10-19T10:00:00Z")
            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.Idle, success))
            service.refresh(ACCOUNT, LIBRARIES)
            val before = refreshes
            refresher().start(scope)
            settle()

            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.LastSyncFailed(SyncFailure.Network), success))
            settle()
            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.FirstSyncRunning(3, 10), success))
            settle()

            assertEquals(before, refreshes)
        }

    @Test
    fun refreshesAreSerialised() =
        runBlocking<Unit> {
            val slow = SlowLibrary(library)
            service.refresh(ACCOUNT, LIBRARIES)
            LineupRefresher(serviceOver(slow), slow, { libraries }) { events += it }.start(scope)
            settle()

            // Both signals at once: the two refreshes they ask for must not overlap.
            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T10:00:00Z"))
            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.Idle, Instant.parse("2026-10-19T10:00:01Z")))
            until("both ran") { slow.completed.get() >= 2 }
            settle()

            assertEquals(1, slow.maxInFlight.get(), "never two at once")
        }

    @Test
    fun aFailingRefreshIsReportedAndTheRefresherKeepsListening() =
        runBlocking<Unit> {
            // Unfinalised, so planning takes the device offset, and 7 minutes is not one the rules allow.
            val unfinalised = Lineup(SERVER, USER, 1, 0, finalised = false, channels = emptyList())
            store.inner.save(ACCOUNT, LineupCodec.encode(unfinalised))
            offset = 7

            refresher().start(scope)
            until("failure reported") { LineupEvent.RefreshFailed in events }

            offset = 0
            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T10:00:00Z"))
            until("a later trigger refreshed") { refreshes >= 1 }
            until("finalised") { service.loaded(ACCOUNT)?.lineup?.finalised == true }
        }

    @Test
    fun nothingRefreshesWithoutAnAccountOrLibraries() =
        runBlocking<Unit> {
            library.delegate.forget(ACCOUNT)
            refresher().start(scope)
            settle()
            assertEquals(0, refreshes)

            libraries = null
            library.delegate.useAccount(ACCOUNT)
            settle()
            assertEquals(0, refreshes, "no libraries chosen yet")

            libraries = emptySet()
            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T10:00:00Z"))
            settle()
            assertEquals(0, refreshes, "an empty choice is no libraries")
        }

    @Test
    fun librariesThrowingOnceDoesNotStopTheRefresher() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val before = refreshes
            var throwOnce = true
            LineupRefresher(
                service,
                library,
                {
                    if (throwOnce) {
                        throwOnce = false
                        error("the settings could not be read")
                    }
                    libraries
                },
            ) { events += it }.start(scope)
            settle()

            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T10:00:00Z"))
            until("failure reported") { LineupEvent.RefreshFailed in events }
            assertEquals(before, refreshes)

            library.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T11:00:00Z"))
            until("a later trigger refreshed") { refreshes == before + 1 }
        }

    @Test
    fun anAccountSwitchNeverRefreshesTheOldAccountWithTheNewOnesLibraries() =
        runBlocking<Unit> {
            val other = LibraryAccount(ServerId(SERVER.value), UserId("d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0"))
            val otherLibraries = setOf(ItemId("e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0"))
            val seen = RecordingLibrary(library)
            val gate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            LineupRefresher(serviceOver(seen), seen, {
                // The read is pending for the first account; the app switches account meanwhile, and the
                // read then answers with the new account's choice.
                entered.complete(Unit)
                withContext(NonCancellable) { gate.await() }
                otherLibraries
            }) { events += it }.start(scope)
            withTimeout(WAIT_MS) { entered.await() }

            library.delegate.useAccount(other)
            delay(SETTLE_MS)
            gate.complete(Unit)
            until("the new account was refreshed") { seen.reads.any { it.first == other } }
            settle()

            assertEquals(emptyList(), seen.reads.filter { it.first == ACCOUNT }, "the old account is never read")
        }

    @Test
    fun startingTwiceStartsOnce() =
        runBlocking<Unit> {
            val slow = SlowLibrary(library)
            val refresher = LineupRefresher(serviceOver(slow), slow, { libraries }) { events += it }
            refresher.start(scope)
            refresher.start(scope)
            until("refreshed") { slow.completed.get() >= 1 }
            settle()

            assertEquals(1, slow.completed.get())
        }

    @Test
    fun aSelectionAlreadyForTheNewAccountIsNeverUsedForTheOldOne() =
        runBlocking<Unit> {
            val other = LibraryAccount(ServerId(SERVER.value), UserId("d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0"))
            val otherLibraries = setOf(ItemId("e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0"))
            val seen = RecordingLibrary(library)
            // The account flow the refresher listens to has not delivered the new account yet, but the port already
            // answers it when asked again, and the selection is already the new account's.
            val lagging = LaggingActiveAccount(seen, stale = ACCOUNT, now = other)
            LineupRefresher(serviceOver(seen), lagging, { otherLibraries }) { events += it }.start(scope)
            settle()

            assertEquals(emptyList(), seen.reads, "no refresh with the other account's libraries")
        }

    // Ruling 2: the lineup is not part of the session's saved library. Signing out, or a rejected session, forgets the
    // saved library (`AccountCache.forget`) and leaves the lineup file alone.
    @Test
    fun sessionExpiryKeepsTheLineup() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val saved = checkNotNull(store.inner.peek(ACCOUNT))
            val info = ServerInfo("Home", ServerId(SERVER.value), ServerVersion(12, 2, 0))
            val address = ServerAddress(Scheme.Https, "jellyfin.example", 443)
            val session = Session(UserId(USER.value), AccessToken("fake-token"), info, address, null, false)
            val server = FakeMediaServer()
            val sessions = SessionRepository(InMemorySecretStore(), server, library.delegate)

            sessions.save(session)
            server.resumeResult = ServerResult.Failure(MediaServerError.SessionExpired())
            sessions.restore(address)
            sessions.signOut(session)

            assertTrue(library.delegate.forgotten.isNotEmpty(), "the saved library was forgotten")
            assertTrue(saved.contentEquals(store.inner.peek(ACCOUNT)), "the lineup file is untouched")
            assertTrue(store.calls.none { it == "forget" || it == "setAside" }, "the store was never told")
            assertTrue(store.inner.load(ACCOUNT) is StoredBytes.Present)
        }
}

private const val WAIT_MS = 10_000L
private const val SETTLE_MS = 250L

/** A library whose planning read takes a moment, and notes how many run at once. */
private class SlowLibrary(
    private val inner: CountingLibrary,
) : LibraryRepository by inner {
    val inFlight = AtomicInteger()
    val maxInFlight = AtomicInteger()
    val completed = AtomicInteger()

    override suspend fun ruleSnapshot(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        cutoff: Instant,
    ): RuleSnapshot {
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
        try {
            delay(SLOW_MS)
            return inner.ruleSnapshot(account, libraries, cutoff)
        } finally {
            inFlight.decrementAndGet()
            completed.incrementAndGet()
        }
    }
}

private const val SLOW_MS = 80L

/** A library that notes which account and libraries each planning read was for. */
private class RecordingLibrary(
    private val inner: CountingLibrary,
) : LibraryRepository by inner {
    val reads: MutableList<Pair<LibraryAccount, Set<ItemId>>> = CopyOnWriteArrayList()

    override suspend fun ruleSnapshot(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        cutoff: Instant,
    ): RuleSnapshot {
        reads += account to libraries
        return inner.ruleSnapshot(account, libraries, cutoff)
    }
}

/** A library whose first [activeAccount] flow is stale (it never moves on), while any later ask gives the true one. */
private class LaggingActiveAccount(
    private val inner: LibraryRepository,
    private val stale: LibraryAccount,
    private val now: LibraryAccount,
) : LibraryRepository by inner {
    private val asked = AtomicInteger()

    override fun activeAccount(): Flow<LibraryAccount?> =
        if (asked.getAndIncrement() == 0) {
            flow {
                emit(stale)
                awaitCancellation()
            }
        } else {
            flowOf(now)
        }
}
