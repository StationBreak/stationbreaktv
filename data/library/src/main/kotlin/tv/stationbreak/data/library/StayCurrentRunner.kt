// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.RestoreOutcome
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * Which of the two stay-current passes a run does: the incremental pass (items changed since the last one) and the ID
 * reconciliation (deletions, and items the cache lacks). When both are asked for, the incremental pass goes first.
 */
data class SyncPlan(
    val incremental: Boolean,
    val reconcile: Boolean,
) {
    /** True when there is nothing to run. */
    val isEmpty: Boolean get() = !incremental && !reconcile

    companion object {
        /** Neither pass. */
        val None = SyncPlan(incremental = false, reconcile = false)

        /** Both passes: what the periodic job runs. */
        val Both = SyncPlan(incremental = true, reconcile = true)
    }
}

/**
 * What the quiet worker runs (spec 06 task 4.7), after the first sync: the stay-current passes of a [SyncPlan]. It
 * reads the stored server, the ticked libraries and the session from the app's own stores when it runs, the way
 * [FirstSyncRunner] does, so no token passes through WorkManager. It is quiet by construction: it only ever writes the
 * saved library, never shows anything, and never puts the account back into the first-sync state.
 *
 * After a reconcile that completed it also purges the account's history older than the retention (spec 09, Unit 3).
 * The purge is housekeeping: a purge that fails, or is skipped for a clock that looks wrong, is kept in [lastPurge] and
 * never fails the run.
 *
 * Last of all, in a run with the incremental pass that completed, it runs the studios re-read while one is pending
 * (spec 18, ruling R9; see [StayCurrentEngine.backfill]). A reconcile that failed does not stop it (a library that is
 * now legitimately empty fails every reconcile), but one that was cancelled does. It goes after everything else, so a
 * re-read that is stopped by WorkManager's window or fails never costs the other passes; its result is kept in
 * [lastBackfill] and never changes how the run ended.
 *
 * It reports timings through [report], numbers, flags and outcome names only (never a title, a name, an ID, an
 * address or a message), so a device log shows whether the re-read ran and how long it took. Durations come from
 * [timeSource], which is monotonic, not from [clock].
 *
 * Only one run goes at a time in the process (a second one, from another trigger, does nothing), and a run leaves an
 * account whose first sync has not finished to the first sync. A failure is recorded in the saved library for Phase 1's
 * banner to read; none of it retries, because the next open or the next periodic run is the retry.
 */
@Suppress("LongParameterList") // The same stores the first-sync runner is given.
class StayCurrentRunner(
    private val settings: ServerSettings,
    private val sessions: SessionRepository,
    private val server: MediaServer,
    private val repository: RoomLibraryRepository,
    private val clock: Clock = Clock.System,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val report: (String) -> Unit = {},
) {
    private val gate = Mutex()

    // What a walk could not find, kept across runs in this process.
    private val unfindable = UnfindableIds()

    /** How the last purge of this process ended, or null if none has run: the record of a skip or a failure. */
    internal var lastPurge: PurgeOutcome? = null
        private set

    /**
     * How the last collection pass of this process ended, or null if none has run: the record of a failure. Like a
     * failed purge, a failed collection pass never fails the run (the next run is the retry), and it is not written to
     * the account's sync state.
     */
    internal var lastCollectionPass: CollectionResult? = null
        private set

    /**
     * How the last studios re-read of this process ended, or null if none has run: the record of a failure and of the
     * items it passed over. Like a failed collection pass, it never fails the run and is not written to the account's
     * sync state; the next run with the incremental pass is the retry.
     */
    internal var lastBackfill: StayResult<PassSummary>? = null
        private set

    /** Test seam: the purge the run does after a completed reconcile. The repository's own in the app. */
    internal var purge: suspend (LibraryAccount, Instant?) -> PurgeOutcome =
        { account, reference -> repository.purgeHistory(account, reference) }

    /** True while a run is going. The trigger policy reads it so an open during a sync starts nothing. */
    val isRunning: Boolean get() = gate.isLocked

    /** Runs [plan] once and says how it ended for WorkManager. Never asks to be retried. */
    suspend fun run(plan: SyncPlan): SyncVerdict =
        when (outcome(plan)) {
            SyncOutcome.Completed, SyncOutcome.Cancelled -> SyncVerdict.Success
            is SyncOutcome.Failed -> SyncVerdict.Failure
        }

    internal suspend fun outcome(plan: SyncPlan): SyncOutcome {
        if (plan.isEmpty || !gate.tryLock()) return SyncOutcome.Cancelled
        try {
            return guarded(plan)
        } finally {
            gate.unlock()
        }
    }

    // A sign-out that lands while a pass is writing ends the pass with nothing more written.
    private suspend fun guarded(plan: SyncPlan): SyncOutcome =
        try {
            start(plan)
        } catch (_: AccountNotActiveException) {
            SyncOutcome.Cancelled
        }

    @Suppress("ReturnCount") // Each early return is a reason there is nothing to do.
    private suspend fun start(plan: SyncPlan): SyncOutcome {
        val record = settings.load() ?: return SyncOutcome.Cancelled
        val chosen = settings.loadLibraries()?.takeIf { it.isNotEmpty() } ?: return SyncOutcome.Cancelled
        val active = repository.dao.activeAccounts().singleOrNull() ?: return SyncOutcome.Cancelled
        val account = LibraryAccount(ServerId(active.serverId), UserId(active.userId))
        // The first sync has not ended: it owns the library, and nothing here is a "top up" yet.
        val firstSyncOpen = repository.lastSuccess(account) == null || repository.isFirstSyncRunning(account)
        if (firstSyncOpen) return SyncOutcome.Cancelled
        return when (val restored = sessions.verify(record.address)) {
            is RestoreOutcome.Restored -> safely(account) { passes(restored.session, account, chosen, plan) }
            RestoreOutcome.SignedOut -> SyncOutcome.Cancelled
            is RestoreOutcome.Rejected -> record(account, SyncFailure.SessionExpired)
            is RestoreOutcome.Unavailable -> record(account, restored.error.toSyncFailure())
        }
    }

    private suspend fun passes(
        session: Session,
        account: LibraryAccount,
        chosen: Set<ItemId>,
        plan: SyncPlan,
    ): SyncOutcome {
        // Signed out, or another account in, while the server was answering: nothing to sync.
        if (session.server.id != account.server || session.userId != account.user) return SyncOutcome.Cancelled
        val engine = StayCurrentEngine(repository, server, clock, unfindable = unfindable)
        report.say("StayCurrent incremental=${plan.incremental} reconcile=${plan.reconcile}")
        val began = timeSource.markNow()
        val ran =
            try {
                stayCurrent(engine, session, account, chosen, plan)
            } catch (stopped: CancellationException) {
                report.say("StayCurrentDone ms=${began.elapsedNow().inWholeMilliseconds} outcome=Cancelled")
                throw stopped
            } catch (signedOut: AccountNotActiveException) {
                report.say("StayCurrentDone ms=${began.elapsedNow().inWholeMilliseconds} outcome=Cancelled")
                throw signedOut
            }
        val outcome = ran.outcome
        report.say("StayCurrentDone ms=${began.elapsedNow().inWholeMilliseconds} ${outcome.label()}")
        // The re-read needs the incremental pass to have completed; a reconcile that failed is no reason to skip it.
        if (ran.incrementalCompleted && outcome != SyncOutcome.Cancelled) backfill(engine, session, account, chosen)
        return outcome
    }

    @Suppress("ReturnCount") // Each early return is a pass that did not complete.
    private suspend fun stayCurrent(
        engine: StayCurrentEngine,
        session: Session,
        account: LibraryAccount,
        chosen: Set<ItemId>,
        plan: SyncPlan,
    ): Ran {
        // The incremental pass records a new success; the purge's clock guard needs the one from before this run.
        val reference = repository.lastSuccess(account)
        if (plan.incremental) {
            val incremental = engine.incremental(session, account, chosen).outcome
            if (incremental != SyncOutcome.Completed) return Ran(incremental, incrementalCompleted = false)
        }
        if (!plan.reconcile) {
            collections(engine, session, account)
            return Ran(SyncOutcome.Completed, incrementalCompleted = plan.incremental)
        }
        val reconciled = engine.reconcile(session, account, chosen).outcome
        if (reconciled == SyncOutcome.Completed) {
            collections(engine, session, account)
            purgeHistory(account, reference)
        }
        return Ran(reconciled, incrementalCompleted = plan.incremental)
    }

    // The collection pass follows the item passes. Its failure is kept for the record and never fails the run;
    // cancellation and a sign-out pass through, the same as for the passes.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun collections(
        engine: StayCurrentEngine,
        session: Session,
        account: LibraryAccount,
    ) {
        lastCollectionPass =
            try {
                engine.collections(session, account)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (signedOut: AccountNotActiveException) {
                throw signedOut
            } catch (_: Exception) {
                CollectionResult(SyncOutcome.Failed(SyncFailure.Server), CollectionSummary())
            }
    }

    // The studios re-read, after everything else. Its result is kept for the record and never changes the run's;
    // cancellation and a sign-out pass through, the same as for the passes.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun backfill(
        engine: StayCurrentEngine,
        session: Session,
        account: LibraryAccount,
        chosen: Set<ItemId>,
    ) {
        val pending = repository.pendingOrNull(account)
        report.say("Backfill pending=${pending ?: "unknown"}")
        val began = timeSource.markNow()
        val result =
            try {
                engine.backfill(session, account, chosen)
            } catch (cancelled: CancellationException) {
                if (pending != false) {
                    report.say("BackfillDone ms=${began.elapsedNow().inWholeMilliseconds} outcome=Cancelled")
                }
                throw cancelled
            } catch (signedOut: AccountNotActiveException) {
                throw signedOut
            } catch (_: Exception) {
                StayResult(SyncOutcome.Failed(SyncFailure.Server), PassSummary())
            }
        lastBackfill = result
        if (pending == false) return
        val read = result.summary
        report.say(
            "BackfillDone ms=${began.elapsedNow().inWholeMilliseconds} ${result.outcome.label()} " +
                "inserted=${read.inserted} updated=${read.updated} unchanged=${read.unchanged} " +
                "skipped=${read.skipped} stillPending=${repository.pendingOrNull(account) ?: "unknown"}",
        )
    }

    // Housekeeping after a pass that completed: a failure is kept and never fails the run. Cancellation and a sign-out
    // pass through, the same as for the passes.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun purgeHistory(
        account: LibraryAccount,
        reference: Instant?,
    ) {
        lastPurge =
            try {
                purge(account, reference)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (signedOut: AccountNotActiveException) {
                throw signedOut
            } catch (failure: Exception) {
                PurgeOutcome.Failed(failure)
            }
    }

    // Anything unexpected is a server failure for the record, never a crash; cancellation and a sign-out pass through.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun safely(
        account: LibraryAccount,
        block: suspend () -> SyncOutcome,
    ): SyncOutcome =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (signedOut: AccountNotActiveException) {
            throw signedOut
        } catch (_: Exception) {
            record(account, SyncFailure.Server)
        }

    // The server could not be asked, or rejected the session: the saved library keeps what it has, and says why. A
    // first sync that started meanwhile is left alone.
    private suspend fun record(
        account: LibraryAccount,
        failure: SyncFailure,
    ): SyncOutcome {
        if (!repository.failStayCurrent(account, failure)) return SyncOutcome.Cancelled
        return SyncOutcome.Failed(failure)
    }
}

// How the passes of a run ended, and whether the incremental pass was in the plan and completed (the re-read's cue).
private class Ran(
    val outcome: SyncOutcome,
    val incrementalCompleted: Boolean,
)

// The outcome as a log field: the class name, and for a failure the SyncFailure name; never a message.
private fun SyncOutcome.label() =
    when (this) {
        SyncOutcome.Completed -> "outcome=Completed"
        SyncOutcome.Cancelled -> "outcome=Cancelled"
        is SyncOutcome.Failed -> "outcome=Failed failure=${failure.name}"
    }

// The re-read flag for the log only: null when it cannot be read, so a logging read never fails the run.
@Suppress("TooGenericExceptionCaught")
private suspend fun RoomLibraryRepository.pendingOrNull(account: LibraryAccount): Boolean? =
    try {
        backfillPending(account)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

// A report that throws must never change how the run ends.
@Suppress("TooGenericExceptionCaught")
private fun ((String) -> Unit).say(line: String) {
    try {
        invoke(line)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Logging is best effort.
    }
}
