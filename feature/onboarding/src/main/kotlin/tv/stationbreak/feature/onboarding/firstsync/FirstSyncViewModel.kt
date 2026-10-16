// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.firstsync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.FirstSync
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.feature.onboarding.server.ErrorCopyTable
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The first-sync progress screen (spec 06, Unit 3; owner answer 3). It shows only until the signed-in account's first
 * sync has ended, and never again for a later one: an account with a last-success time, or nobody signed in at the
 * start, finishes at once. It asks for the sync to be queued (a no-op when it already is), shows the saved library's
 * own count and total, at most twice a second, and on a failure shows the error table's words with Retry.
 *
 * Leaving the screen (Back) does not stop the sync: it is WorkManager's, and the port has no way to cancel it. The
 * progress screen is simply back on the next open.
 */
@HiltViewModel
internal class FirstSyncViewModel
    @Inject
    constructor(
        private val firstSync: FirstSync,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<FirstSyncPhase>(FirstSyncPhase.Starting)
        val state: StateFlow<FirstSyncPhase> = mutableState.asStateFlow()

        private val results = Channel<FirstSyncEvent>(Channel.BUFFERED)

        /** Fires when the screen's work is over. */
        val events: Flow<FirstSyncEvent> = results.receiveAsFlow()

        // How long the TV has gone without saving a first page: nothing yet, a waiting line, or the failure screen.
        private val wait = MutableStateFlow(Wait.None)
        private var timer: Job? = null

        private var seenAccount = false
        private var asked = false
        private var finished = false

        init {
            startTimer()
            viewModelScope.launch {
                combine(firstSync.status().onEach(::react), wait) { status, waited -> phaseOf(status, waited) }
                    .filterNotNull()
                    .throttleLatest(UPDATE_INTERVAL)
                    .collect { mutableState.value = it }
            }
        }

        // Everything that must happen for every status, however fast they arrive.
        private suspend fun react(status: LibraryStatus?) {
            val first = !seenAccount
            seenAccount = seenAccount || status != null
            if ((status?.state as? SyncState.FirstSyncRunning)?.copied?.let { it > 0 } == true) {
                // A first page is saved, so the network is there: no waiting line, and no timeout.
                timer?.cancel()
                wait.value = Wait.None
            }
            when {
                // Nobody is signed in at the start: nothing to wait for. Nobody after an account was: it was forgotten.
                status == null -> if (first) finish() else results.send(FirstSyncEvent.SessionLost)

                status.lastSuccess != null -> finish()

                status.state is SyncState.NeverSynced || status.state is SyncState.FirstSyncRunning -> ask()
            }
        }

        private suspend fun finish() {
            if (finished) return
            finished = true
            results.send(FirstSyncEvent.Finished)
        }

        private fun ask() {
            if (asked) return
            asked = true
            firstSync.ensureRunning()
        }

        // With no network the work waits for one and the saved library says nothing, so time stands in for the news
        // (owner decision, PR #250): a waiting line after 15 seconds, the failure screen after 5 minutes.
        private fun startTimer() {
            timer?.cancel()
            wait.value = Wait.None
            timer =
                viewModelScope.launch {
                    delay(WAITING_AFTER)
                    wait.value = Wait.Waiting
                    delay(FAILED_AFTER - WAITING_AFTER)
                    wait.value = Wait.TimedOut
                }
        }

        private fun phaseOf(
            status: LibraryStatus?,
            waited: Wait,
        ): FirstSyncPhase? {
            val phase = if (status == null || status.lastSuccess != null) null else phaseOf(status.state)
            val nothingSaved = phase == FirstSyncPhase.Starting || (phase as? FirstSyncPhase.Syncing)?.copied == 0
            return when {
                !nothingSaved -> phase
                waited == Wait.Waiting -> FirstSyncPhase.WaitingForNetwork
                waited == Wait.TimedOut -> failed(SyncFailure.Network)
                else -> phase
            }
        }

        private fun phaseOf(state: SyncState): FirstSyncPhase? =
            when (state) {
                SyncState.NeverSynced -> FirstSyncPhase.Starting
                is SyncState.FirstSyncRunning -> FirstSyncPhase.Syncing(state.copied, state.total)
                SyncState.Idle -> FirstSyncPhase.Starting
                is SyncState.LastSyncFailed -> failed(state.failure)
            }

        private fun failed(failure: SyncFailure): FirstSyncPhase {
            val error =
                when (failure) {
                    SyncFailure.Network -> MediaServerError.NetworkUnreachable()
                    SyncFailure.SessionExpired -> MediaServerError.SessionExpired()
                    SyncFailure.Server, SyncFailure.Suspect -> MediaServerError.ServerError()
                }
            return FirstSyncPhase.Failed(error, ErrorCopyTable.copyFor(error), failure == SyncFailure.SessionExpired)
        }

        /**
         * Retry: runs the sync again. When the server no longer accepts the sign-in that cannot help, so Retry goes
         * back to launch routing, which asks the user to sign in again.
         */
        fun onRetry() {
            val failed = mutableState.value as? FirstSyncPhase.Failed ?: return
            if (failed.sessionLost) {
                viewModelScope.launch { results.send(FirstSyncEvent.SessionLost) }
            } else {
                firstSync.retry()
                startTimer()
                mutableState.value = FirstSyncPhase.Starting
            }
        }

        private companion object {
            /** The count changes at most twice a second (Design Considerations): no animation, a light screen. */
            val UPDATE_INTERVAL = 500.milliseconds
            val WAITING_AFTER = 15.seconds
            val FAILED_AFTER = 5.minutes
        }
    }

private enum class Wait { None, Waiting, TimedOut }

// The first value at once, then the latest no more often than every [period].
private fun <T> Flow<T>.throttleLatest(period: Duration): Flow<T> =
    conflate().transform { value ->
        emit(value)
        delay(period)
    }
