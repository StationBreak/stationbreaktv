// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.firstsync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.stationbreak.core.domain.InMemoryFirstSync
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.feature.onboarding.server.ErrorCopyTable
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Spec 06 task 3.8: the first-sync progress screen's state. It shows only until an account's first sync ends and never
 * after, the count and total come from the saved library's status (page one's total), updates are limited to twice a
 * second, a failure offers Retry, and leaving the screen leaves the sync running.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstSyncProgressViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val finishedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)

    @Before
    fun setMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMain() = Dispatchers.resetMain()

    private val never = LibraryStatus(SyncState.NeverSynced, null)

    private fun running(
        copied: Int,
        total: Int?,
    ) = LibraryStatus(SyncState.FirstSyncRunning(copied, total), null)

    private fun failed(failure: SyncFailure) = LibraryStatus(SyncState.LastSyncFailed(failure), null)

    private fun TestScope.events(vm: FirstSyncViewModel): List<FirstSyncEvent> {
        val seen = mutableListOf<FirstSyncEvent>()
        backgroundScope.launch { vm.events.collect { seen += it } }
        return seen
    }

    // When it shows.

    @Test
    fun withNobodySignedInThereIsNothingToWaitForAndNothingIsStarted() =
        runTest(dispatcher) {
            val firstSync = InMemoryFirstSync(null)
            val vm = FirstSyncViewModel(firstSync)
            val seen = events(vm)

            runCurrent()

            assertEquals(listOf(FirstSyncEvent.Finished), seen)
            assertEquals(0, firstSync.ensureCalls)
        }

    @Test
    fun anAccountWhoseFirstSyncEndedNeverSeesTheScreen() =
        runTest(dispatcher) {
            val firstSync = InMemoryFirstSync(LibraryStatus(SyncState.Idle, finishedAt))
            val vm = FirstSyncViewModel(firstSync)
            val seen = events(vm)

            runCurrent()

            assertEquals(listOf(FirstSyncEvent.Finished), seen)
            assertEquals(0, firstSync.ensureCalls)
        }

    @Test
    fun aLaterSyncIsNeverShownEvenWhileItRunsOrAfterItFails() =
        runTest(dispatcher) {
            val firstSync = InMemoryFirstSync(LibraryStatus(SyncState.FirstSyncRunning(10, 100), finishedAt))
            val vm = FirstSyncViewModel(firstSync)
            val seen = events(vm)

            runCurrent()
            firstSync.current = LibraryStatus(SyncState.LastSyncFailed(SyncFailure.Network), finishedAt)
            runCurrent()

            assertEquals(FirstSyncEvent.Finished, seen.first())
            assertEquals(FirstSyncPhase.Starting, vm.state.value)
        }

    @Test
    fun theScreenEndsWhenTheFirstSyncDoes() =
        runTest(dispatcher) {
            val firstSync = InMemoryFirstSync(running(40, 100))
            val vm = FirstSyncViewModel(firstSync)
            val seen = events(vm)
            runCurrent()
            assertEquals(emptyList<FirstSyncEvent>(), seen)

            firstSync.current = LibraryStatus(SyncState.Idle, finishedAt)
            runCurrent()
            firstSync.current = LibraryStatus(SyncState.Idle, finishedAt + 1.minutes)
            runCurrent()

            assertEquals(listOf(FirstSyncEvent.Finished), seen)
        }

    // The first sync is started by the screen, once.

    @Test
    fun anAccountThatNeverSyncedHasTheFirstSyncStartedOnce() =
        runTest(dispatcher) {
            val firstSync = InMemoryFirstSync(never)
            val vm = FirstSyncViewModel(firstSync)

            runCurrent()
            assertEquals(FirstSyncPhase.Starting, vm.state.value)
            firstSync.current = running(0, null)
            runCurrent()

            assertEquals(1, firstSync.ensureCalls)
            assertEquals(FirstSyncPhase.Syncing(0, null), vm.state.value)
        }

    @Test
    fun aSyncThatWasRunningWhenTheAppWasKilledIsEnsuredAgain() =
        runTest(dispatcher) {
            val firstSync = InMemoryFirstSync(running(300, 1000))
            FirstSyncViewModel(firstSync)

            runCurrent()

            assertEquals(1, firstSync.ensureCalls)
        }

    // The count.

    @Test
    fun theCountAndTotalAreWhatTheSavedLibraryReports() =
        runTest(dispatcher) {
            val vm = FirstSyncViewModel(InMemoryFirstSync(running(12_500, 15_000)))

            runCurrent()

            assertEquals(FirstSyncPhase.Syncing(copied = 12_500, total = 15_000), vm.state.value)
        }

    @Test
    fun beforeTheTotalIsKnownThereIsOnlyACount() =
        runTest(dispatcher) {
            val vm = FirstSyncViewModel(InMemoryFirstSync(running(200, null)))

            runCurrent()

            assertEquals(FirstSyncPhase.Syncing(copied = 200, total = null), vm.state.value)
        }

    @Test
    fun theFractionIsNeverPastTheEnd() {
        assertEquals(0.5f, FirstSyncPhase.Syncing(5, 10).fraction, 0f)
        assertEquals(1f, FirstSyncPhase.Syncing(11, 10).fraction, 0f)
        assertEquals(0f, FirstSyncPhase.Syncing(5, 0).fraction, 0f)
        assertEquals(0f, FirstSyncPhase.Syncing(5, null).fraction, 0f)
    }

    // At most twice a second.

    @Test
    fun theCountChangesAtMostTwiceASecond() =
        runTest(dispatcher) {
            val firstSync = InMemoryFirstSync(running(0, 1000))
            val vm = FirstSyncViewModel(firstSync)
            val shown = mutableListOf<FirstSyncPhase>()
            backgroundScope.launch { vm.state.collect { shown += it } }
            runCurrent()

            // A page every 50 ms for two seconds.
            repeat(40) { page ->
                firstSync.current = running(page * 10 + 10, 1000)
                advanceTimeBy(50)
            }
            advanceTimeBy(600)

            val counts = shown.filterIsInstance<FirstSyncPhase.Syncing>()
            // Two seconds at two a second is four, plus the first one shown at once.
            assertTrue("shown ${counts.size} times in 2.6 seconds", counts.size <= 6)
            assertTrue("shown only ${counts.size} times", counts.size >= 3)
            assertEquals(FirstSyncPhase.Syncing(400, 1000), shown.last())
        }

    // Failure.

    @Test
    fun aNetworkFailureShowsThePlainErrorFromTheTableWithRetry() =
        runTest(dispatcher) {
            val vm = FirstSyncViewModel(InMemoryFirstSync(failed(SyncFailure.Network)))

            runCurrent()

            val phase = vm.state.value as FirstSyncPhase.Failed
            assertTrue(phase.error is MediaServerError.NetworkUnreachable)
            assertEquals(ErrorCopyTable.copyFor(phase.error), phase.copy)
            assertEquals(false, phase.sessionLost)
        }

    @Test
    fun aFailureShowsWithinHalfASecondOfTheLastCount() =
        runTest(dispatcher) {
            val firstSync = InMemoryFirstSync(running(100, 1000))
            val vm = FirstSyncViewModel(firstSync)
            runCurrent()

            firstSync.current = failed(SyncFailure.Server)
            advanceTimeBy(500)
            runCurrent()

            assertTrue(vm.state.value is FirstSyncPhase.Failed)
        }

    @Test
    fun retryRunsTheSyncAgainAndGoesBackToWaiting() =
        runTest(dispatcher) {
            val firstSync = InMemoryFirstSync(failed(SyncFailure.Network))
            val vm = FirstSyncViewModel(firstSync)
            runCurrent()

            vm.onRetry()
            runCurrent()

            assertEquals(1, firstSync.retryCalls)
            assertEquals(FirstSyncPhase.Starting, vm.state.value)
        }

    @Test
    fun aRejectedSessionOffersRetryThatGoesBackToSigningIn() =
        runTest(dispatcher) {
            val firstSync = InMemoryFirstSync(failed(SyncFailure.SessionExpired))
            val vm = FirstSyncViewModel(firstSync)
            val seen = events(vm)
            runCurrent()

            val phase = vm.state.value as FirstSyncPhase.Failed
            assertTrue(phase.error is MediaServerError.SessionExpired)
            assertEquals(true, phase.sessionLost)
            vm.onRetry()
            runCurrent()

            assertEquals(listOf(FirstSyncEvent.SessionLost), seen)
            assertEquals(0, firstSync.retryCalls)
        }

    @Test
    fun anAccountThatVanishesWhileTheScreenShowsMeansTheSessionWasLost() =
        runTest(dispatcher) {
            val firstSync = InMemoryFirstSync(running(10, 100))
            val vm = FirstSyncViewModel(firstSync)
            val seen = events(vm)
            runCurrent()

            firstSync.current = null
            runCurrent()

            assertEquals(listOf(FirstSyncEvent.SessionLost), seen)
        }

    // Back.

    @Test
    fun leavingTheScreenLeavesTheSyncRunning() =
        runTest(dispatcher) {
            val firstSync = InMemoryFirstSync(running(10, 100))
            val owner = ViewModelStore()
            val factory = factoryOf(FirstSyncViewModel(firstSync))
            val vm = ViewModelProvider.create(owner, factory)[FirstSyncViewModel::class.java]
            runCurrent()

            // Back pops the screen, which clears its ViewModel.
            owner.clear()
            runCurrent()

            // Nothing was cancelled or restarted: the port has no way to stop the sync, and only one start was asked for.
            assertEquals(1, firstSync.ensureCalls)
            assertEquals(0, firstSync.retryCalls)
            assertTrue(vm.state.value is FirstSyncPhase.Syncing)
        }

    private fun factoryOf(vm: FirstSyncViewModel) =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        }
}
