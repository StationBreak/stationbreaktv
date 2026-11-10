// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncNow
import tv.stationbreak.core.domain.SyncNowState
import tv.stationbreak.core.domain.selection.LibraryChoices
import tv.stationbreak.core.domain.selection.LibraryListing
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Sync now over the stay-current [runner] (spec 26, Task 5.1). A press lists the server's libraries once, through the
 * selection's [choices] (which follows that listing: it is the press's one listing for the two-read rule), waits until
 * the selection has followed it, then runs both passes with [StayCurrentRunner.runWaiting], which waits behind a run
 * already under way, so two never run at once, and does not list the server again. A listing that fails ends the
 * press with nothing written (`changedNothing`); a run that fails may have written before it failed, and says so. The
 * passes' writes reach today's schedule the way any run's do: the lineup refresher repairs the blocks whose item left
 * the library after a run that wrote.
 *
 * A press that has not ended within [timeout] (a run that hangs and cannot be cancelled, say) ends as a server failure
 * and the work is cancelled, so the row never waits for ever. Work that does not stop is left behind, and the next
 * press waits behind it, as it does for any run under way.
 *
 * Presses run on [scope]. [report] gets one numbers-only line per press: how it ended, never a name, an ID or a
 * message.
 */
class RunnerSyncNow(
    private val choices: LibraryChoices,
    private val runner: StayCurrentRunner,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val report: (String) -> Unit = {},
) : SyncNow {
    private val shown = MutableStateFlow<SyncNowState>(SyncNowState.Idle)
    private val lock = Any()

    override val state: StateFlow<SyncNowState> = shown.asStateFlow()

    companion object {
        /** How long a press may take before it ends as a server failure. */
        val DEFAULT_TIMEOUT: Duration = 15.minutes
    }

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

    // The press, held to [timeout]: the work runs on its own, so a hang that cannot be cancelled still lets the press
    // end. A press ending this way may have come after a write, so it never says nothing changed.
    private suspend fun press(): SyncNowState {
        val work = scope.async { sync() }
        return try {
            withTimeoutOrNull(timeout) { work.await() }
                ?: SyncNowState.Failed(SyncFailure.Server, changedNothing = false)
        } finally {
            work.cancel()
        }
    }

    // Anything unexpected is a server failure, never a crash; cancellation passes through. It may have come after a
    // write, so it never says nothing changed.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun sync(): SyncNowState =
        try {
            listThenSync()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SyncNowState.Failed(SyncFailure.Server, changedNothing = false)
        }

    @Suppress("ReturnCount") // Each early return is a listing that ends the press.
    private suspend fun listThenSync(): SyncNowState {
        val listing = choices.listing()
        var listedFor: LibraryAccount? = null
        val libraries =
            when (listing) {
                is LibraryListing.Listed -> {
                    listedFor = listing.account
                    listing.libraries
                }

                is LibraryListing.Unavailable -> {
                    return beforeSyncing(listing.error.toSyncFailure())
                }

                // Nobody signed in: the session is what is missing.
                is LibraryListing.NotSignedIn -> {
                    return beforeSyncing(SyncFailure.SessionExpired)
                }
            }
        // An empty listing is the answer the selection does not trust (it changes nothing): neither does Sync now.
        if (libraries.isEmpty()) return beforeSyncing(SyncFailure.Suspect)
        // The run reads the choice this listing may change: it waits until the selection has followed it.
        choices.followed()
        val outcome =
            runner.runWaiting(
                SyncPlan.Both,
                followServer = false,
                listed = ServerResult.Success(libraries),
                listedFor = listedFor,
            )
        return when (outcome) {
            SyncOutcome.Completed -> SyncNowState.Done(clock.now().toEpochMilliseconds())

            // A pass may have written before another failed, and the ended holds may have expired.
            is SyncOutcome.Failed -> SyncNowState.Failed(outcome.failure, changedNothing = false)

            SyncOutcome.Cancelled -> SyncNowState.Idle
        }
    }
}

// A press that stopped for [failure] before it synced: nothing was written.
private fun beforeSyncing(failure: SyncFailure) = SyncNowState.Failed(failure, changedNothing = true)

// The state as a log field: its name, and for a failure the SyncFailure name; never a time or a message.
private fun SyncNowState.label(): String =
    when (this) {
        is SyncNowState.Failed -> "state=Failed failure=${failure.name}"
        is SyncNowState.Done -> "state=Done"
        SyncNowState.Idle -> "state=Idle"
        SyncNowState.Running -> "state=Running"
    }
