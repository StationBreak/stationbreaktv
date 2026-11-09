// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncNow
import tv.stationbreak.core.domain.SyncNowState
import tv.stationbreak.core.domain.selection.LibraryChoices
import tv.stationbreak.core.domain.selection.LibraryListing
import kotlin.time.Clock

/**
 * Sync now over the stay-current [runner] (spec 26, Task 5.1). A press lists the server's libraries once, through the
 * selection's [choices] (which follows that listing: it is the press's one listing for the two-read rule), then runs
 * both passes with [StayCurrentRunner.runWaiting], which waits behind a run already under way, so two never run at
 * once, and does not list the server again. A listing that fails ends the press with nothing written. The passes'
 * writes reach today's schedule the way any run's do: the lineup refresher repairs the blocks whose item left the
 * library after a run that wrote.
 *
 * Presses run on [scope]. [report] gets one numbers-only line per press: how it ended, never a name, an ID or a
 * message.
 */
class RunnerSyncNow(
    private val choices: LibraryChoices,
    private val runner: StayCurrentRunner,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val report: (String) -> Unit = {},
) : SyncNow {
    private val shown = MutableStateFlow<SyncNowState>(SyncNowState.Idle)
    private val lock = Any()

    override val state: StateFlow<SyncNowState> = shown.asStateFlow()

    override fun start(): Boolean {
        synchronized(lock) {
            if (shown.value == SyncNowState.Running) return false
            shown.value = SyncNowState.Running
        }
        val job = scope.launch { end(press()) }
        // A press stopped before it ended (its scope cancelled) leaves nothing running.
        job.invokeOnCompletion { cause -> if (cause != null) end(SyncNowState.Idle) }
        return true
    }

    private fun end(state: SyncNowState) {
        val ended =
            synchronized(lock) {
                (shown.value == SyncNowState.Running).also { if (it) shown.value = state }
            }
        if (ended) runCatching { report("SyncNowDone ${state.label()}") }
    }

    // Anything unexpected is a server failure, never a crash; cancellation passes through.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun press(): SyncNowState =
        try {
            listThenSync()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SyncNowState.Failed(SyncFailure.Server)
        }

    @Suppress("ReturnCount") // Each early return is a listing that ends the press.
    private suspend fun listThenSync(): SyncNowState {
        val libraries =
            when (val listed = choices.listing()) {
                is LibraryListing.Listed -> listed.libraries

                is LibraryListing.Unavailable -> return SyncNowState.Failed(listed.error.toSyncFailure())

                // Nobody signed in: the session is what is missing.
                is LibraryListing.NotSignedIn -> return SyncNowState.Failed(SyncFailure.SessionExpired)
            }
        // An empty listing is the answer the selection does not trust (it changes nothing): neither does Sync now.
        if (libraries.isEmpty()) return SyncNowState.Failed(SyncFailure.Suspect)
        val outcome = runner.runWaiting(SyncPlan.Both, followServer = false, listed = ServerResult.Success(libraries))
        return when (outcome) {
            SyncOutcome.Completed -> SyncNowState.Done(clock.now().toEpochMilliseconds())
            is SyncOutcome.Failed -> SyncNowState.Failed(outcome.failure)
            SyncOutcome.Cancelled -> SyncNowState.Idle
        }
    }
}

// The state as a log field: its name, and for a failure the SyncFailure name; never a time or a message.
private fun SyncNowState.label(): String =
    when (this) {
        is SyncNowState.Failed -> "state=Failed failure=${failure.name}"
        is SyncNowState.Done -> "state=Done"
        SyncNowState.Idle -> "state=Idle"
        SyncNowState.Running -> "state=Running"
    }
