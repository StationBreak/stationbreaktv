// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tv.stationbreak.core.domain.InMemoryLineupStore
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * [LineupService.firstLineup]: Building while the active account has no lineup or the planner still waits for its
 * first choice; Settled once the choice is made (finalised, a pass taken in by a thin library, or the wait given up),
 * and settled for good.
 */
class FirstLineupTest {
    private val library = CountingLibrary()
    private val store = InMemoryLineupStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private val job = Job()
    private val scope = CoroutineScope(job)

    @AfterTest
    fun stop() {
        job.cancel()
    }

    private suspend fun signIn(account: LibraryAccount = ACCOUNT) = library.delegate.useAccount(account)

    private suspend fun now(): FirstLineupState = service.firstLineup().first()

    // Every state the flow gives, from now on.
    private fun seen(): List<FirstLineupState> {
        val states = CopyOnWriteArrayList<FirstLineupState>()
        scope.launch { service.firstLineup().collect { states += it } }
        return states
    }

    private suspend fun until(
        what: String,
        condition: suspend () -> Boolean,
    ) {
        withTimeout(WAIT_MS) { while (!condition()) delay(10) }
        check(condition()) { what }
    }

    // A thin library: two filling recipes of the five a first choice needs, so it is never finalised.
    private fun seedThin() = library.seed(fillingLibrary().take(14))

    @Test
    fun nobodySignedInIsBuilding() =
        runBlocking<Unit> {
            assertEquals(FirstLineupState.Building, now())
        }

    @Test
    fun noLineupYetIsBuilding() =
        runBlocking<Unit> {
            signIn()
            assertEquals(FirstLineupState.Building, now())
        }

    @Test
    fun aLineupWaitingForItsCollectionsIsBuildingThenSettlesWhenAPassIsTakenIn() =
        runBlocking<Unit> {
            signIn()
            library.seed(fillingLibrary())
            library.delegate.setCollectionsSynced(ACCOUNT, false)
            service.refresh(ACCOUNT, LIBRARIES)
            assertEquals(FirstLineupState.Building, now())

            library.delegate.setCollectionsSynced(ACCOUNT, true)
            library.delegate.completeCollectionPass(ACCOUNT, NOON)
            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(true, service.loaded(ACCOUNT)?.lineup?.finalised)
            assertEquals(FirstLineupState.Settled, now())
        }

    @Test
    fun aThinLibraryThatNeverFinalisesSettlesOnceAPassIsTakenIn() =
        runBlocking<Unit> {
            signIn()
            seedThin()
            library.delegate.completeCollectionPass(ACCOUNT, NOON)
            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(false, service.loaded(ACCOUNT)?.lineup?.finalised, "the fixture is thin")
            assertEquals(FirstLineupState.Settled, now())
        }

    @Test
    fun givingUpOnTheCollectionsSettlesItWithANewValue() =
        runBlocking<Unit> {
            signIn()
            seedThin()
            library.delegate.setCollectionsSynced(ACCOUNT, false)
            service.refresh(ACCOUNT, LIBRARIES)
            val states = seen()
            until("building") { states.isNotEmpty() }

            // One pass too many failed: the next plan makes the first choice without them.
            library.delegate.setFailedCollectionPasses(ACCOUNT, GIVE_UP_AFTER)
            assertEquals(listOf(FirstLineupState.Building), states, "nothing settles before that plan")
            service.refresh(ACCOUNT, LIBRARIES)

            until("settled") { states.size == 2 }
            assertEquals(listOf(FirstLineupState.Building, FirstLineupState.Settled), states)
        }

    @Test
    fun aRunWhosePassFailsOnceTooOftenSettlesItThroughTheRefresher() =
        runBlocking<Unit> {
            signIn()
            seedThin()
            library.delegate.setCollectionsSynced(ACCOUNT, false)
            val writing = MutableStateFlow(true)
            LineupRefresher(service, library, { LIBRARIES }, SyncActivity { writing }, NO_CHANGES) {}.start(scope)
            until("the start plan") { service.loaded(ACCOUNT) != null }
            val states = seen()
            until("building") { states == listOf(FirstLineupState.Building) }

            // The run's collection pass fails, the second in a row; then the run ends.
            library.delegate.setFailedCollectionPasses(ACCOUNT, GIVE_UP_AFTER)
            library.delegate.setStatus(ACCOUNT, LibraryStatus(SyncState.Idle, NOON))
            writing.value = false

            until("settled") { states.lastOrNull() == FirstLineupState.Settled }
        }

    @Test
    fun aSettledLineupStaysSettledThroughLaterSyncs() =
        runBlocking<Unit> {
            signIn()
            seedThin()
            library.delegate.completeCollectionPass(ACCOUNT, NOON)
            service.refresh(ACCOUNT, LIBRARIES)
            val states = seen()
            until("settled") { states == listOf(FirstLineupState.Settled) }

            // Later syncs: new content, and a pass that has not come in yet. The choice is already made.
            library.delegate.noteContentChange(ACCOUNT)
            library.delegate.setCollectionsSynced(ACCOUNT, false)
            service.refresh(ACCOUNT, LIBRARIES)
            delay(SETTLE_MS)

            assertEquals(listOf(FirstLineupState.Settled), states)
        }

    @Test
    fun aStoredFinalisedLineupIsSettled() =
        runBlocking<Unit> {
            signIn()
            val finalised = Lineup(SERVER, USER, 1, 0, finalised = true, channels = emptyList())
            store.save(ACCOUNT, LineupCodec.encode(finalised))

            assertEquals(FirstLineupState.Settled, now())
        }

    @Test
    fun anAccountThatCanNeverHaveALineupIsSettled() =
        runBlocking<Unit> {
            // Its IDs are not IDs: no lineup will ever come, so there is nothing to wait for.
            signIn(LibraryAccount(ServerId("not-an-id"), UserId("nor-this")))

            assertEquals(FirstLineupState.Settled, now())
        }
}

private const val WAIT_MS = 10_000L
private const val SETTLE_MS = 250L
