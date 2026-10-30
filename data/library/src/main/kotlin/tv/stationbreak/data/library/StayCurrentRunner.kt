// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import kotlin.time.TimeMark
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
 * address or a message), so a device log shows whether the re-read ran and how long it took. A run or a re-read that
 * ends in an Error (an OutOfMemoryError, say) still says so, as outcome=Error with the class name; the Error goes
 * on. Durations come from [timeSource], which is monotonic, not from [clock].
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
    private val runningNow = MutableStateFlow(false)

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

    /**
     * True while a run holds the gate, from before it reads the settings to after it ends, writing or not. The trigger
     * policy reads it so an open during a sync starts nothing. Compare [running].
     */
    val isRunning: Boolean get() = gate.isLocked

    /**
     * True while a run is writing: from the moment its passes begin (settings read, session verified, the account still
     * the one signed in) to the moment the run ends, whatever ended it. A run that stops before its passes (no server,
     * no libraries, the first sync still open, signed out, the server unreachable) never sets it. That is how it
     * differs from [isRunning], which covers the whole run: the lineup refresher holds the triggers a run's writes
     * cause while this is true and checks once when it turns false, so a run that wrote nothing must not look like one
     * that did (#434).
     */
    val running: StateFlow<Boolean> = runningNow.asStateFlow()

    /** Runs [plan] once and says how it ended for WorkManager. Never asks to be retried. */
    suspend fun run(plan: SyncPlan): SyncVerdict =
        when (outcome(plan)) {
            SyncOutcome.Completed, SyncOutcome.Cancelled -> SyncVerdict.Success
            is SyncOutcome.Failed -> SyncVerdict.Failure
        }

    internal suspend fun outcome(plan: SyncPlan): SyncOutcome {
        val blocked =
            if (plan.isEmpty) {
                Skip.EmptyPlan
            } else if (!gate.tryLock()) {
                Skip.Busy
            } else {
                null
            }
        if (blocked != null) return report.skipped(plan, blocked)
        try {
            return guarded(plan)
        } finally {
            // Here, whatever ended the run, and before the gate opens, so a run that takes it next cannot have its own
            // "true" overwritten.
            runningNow.value = false
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
        val record = settings.load() ?: return report.skipped(plan, Skip.NoServer)
        val libraries = settings.loadLibraries()
        val chosen = libraries?.takeIf { it.isNotEmpty() } ?: return report.skipped(plan, Skip.NoLibraries)
        val accounts = repository.dao.activeAccounts()
        val active = accounts.singleOrNull() ?: return report.skipped(plan, Skip.NoAccount, "accounts=${accounts.size}")
        val account = LibraryAccount(ServerId(active.serverId), UserId(active.userId))
        // The first sync has not ended: it owns the library, and nothing here is a "top up" yet.
        val firstSyncOpen = repository.lastSuccess(account) == null || repository.isFirstSyncRunning(account)
        if (firstSyncOpen) return report.skipped(plan, Skip.FirstSyncOpen)
        return when (val restored = sessions.verify(record.address)) {
            is RestoreOutcome.Restored -> {
                safely(account) { passes(restored.session, account, chosen, plan) }
            }

            RestoreOutcome.SignedOut -> {
                report.skipped(plan, Skip.SignedOut)
            }

            is RestoreOutcome.Rejected -> {
                report.skipped(plan, Skip.SessionRejected)
                record(account, SyncFailure.SessionExpired)
            }

            is RestoreOutcome.Unavailable -> {
                val failure = restored.error.toSyncFailure()
                report.skipped(plan, Skip.Unavailable, "failure=${failure.name}")
                record(account, failure)
            }
        }
    }

    private suspend fun passes(
        session: Session,
        account: LibraryAccount,
        chosen: Set<ItemId>,
        plan: SyncPlan,
    ): SyncOutcome {
        // Signed out, or another account in, while the server was answering: nothing to sync.
        if (session.server.id != account.server || session.userId != account.user) {
            return report.skipped(plan, Skip.AccountChanged)
        }
        runningNow.value = true
        val engine = StayCurrentEngine(repository, server, clock, unfindable = unfindable)
        report.say("StayCurrent incremental=${plan.incremental} reconcile=${plan.reconcile}")
        val generationBefore = repository.generationOrNull(account)
        val began = timeSource.markNow()
        val ran = report.timed(began) { stayCurrent(engine, session, account, chosen, plan) }
        val outcome = ran.outcome
        report.say("StayCurrentDone ms=${began.elapsedNow().inWholeMilliseconds} ${outcome.label()}")
        report.say(wrote(generationBefore, repository.generationOrNull(account), ran))
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
        var passed: PassSummary? = null
        if (plan.incremental) {
            val incremental = engine.incremental(session, account, chosen)
            passed = incremental.summary
            if (incremental.outcome != SyncOutcome.Completed) {
                return Ran(incremental.outcome, incrementalCompleted = false, passed = passed)
            }
        }
        if (!plan.reconcile) {
            val pass = collections(engine, session, account)
            return Ran(SyncOutcome.Completed, plan.incremental, passed, collections = pass.summary)
        }
        val reconciled = engine.reconcile(session, account, chosen)
        var pass: CollectionResult? = null
        if (reconciled.outcome == SyncOutcome.Completed) {
            pass = collections(engine, session, account)
            purgeHistory(account, reference)
        }
        return Ran(reconciled.outcome, plan.incremental, passed, reconciled.summary, pass?.summary)
    }

    // The collection pass follows the item passes. Its failure is kept for the record and never fails the run;
    // cancellation and a sign-out pass through, the same as for the passes.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun collections(
        engine: StayCurrentEngine,
        session: Session,
        account: LibraryAccount,
    ): CollectionResult {
        val result =
            try {
                engine.collections(session, account)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (signedOut: AccountNotActiveException) {
                throw signedOut
            } catch (_: Exception) {
                CollectionResult(SyncOutcome.Failed(SyncFailure.Server), CollectionSummary())
            }
        lastCollectionPass = result
        return result
    }

    // The studios re-read, after everything else. Its result is kept for the record and never changes the run's;
    // cancellation and a sign-out pass through, the same as for the passes.
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun backfill(
        engine: StayCurrentEngine,
        session: Session,
        account: LibraryAccount,
        chosen: Set<ItemId>,
    ) {
        val pending = repository.pendingOrNull(account)
        report.say("Backfill pending=${pending ?: "unknown"}")
        val generationBefore = repository.generationOrNull(account)
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
            } catch (fatal: Throwable) {
                // An Error (an OutOfMemoryError, say) is not recorded: it goes on, with a line saying so.
                if (pending != false) {
                    report.sayEscaping("BackfillDone ms=${began.elapsedNow().inWholeMilliseconds} ${fatal.label()}")
                }
                throw fatal
            }
        lastBackfill = result
        // After StayCurrentWrote, which covers only the passes: the re-read's own writes are told apart (#480).
        val read = result.summary
        val moved = generationMoved(generationBefore, repository.generationOrNull(account))
        report.say("BackfillWrote generation=$moved inserted=${read.inserted} updated=${read.updated}")
        if (pending == false) return
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

// How the passes of a run ended, whether the incremental pass was in the plan and completed (the re-read's cue), and
// what each pass that ran wrote (null for one that did not run).
private class Ran(
    val outcome: SyncOutcome,
    val incrementalCompleted: Boolean,
    val passed: PassSummary? = null,
    val reconciled: ReconcileSummary? = null,
    val collections: CollectionSummary? = null,
)

// What a run's passes wrote, numbers only (#480): how many times the content generation moved while they ran (the
// studios re-read after them logs its own BackfillWrote line), and the counts
// behind it, so a device log shows which write moved it. The counts are inserted/updated for the incremental pass,
// tombstoned/revived/fetched/seriesTombstoned/seriesRevived for the reconcile, and inserted/changed/revived/tombstoned
// for the collection pass; a pass that did not run is `none`. A generation that could not be read is `unknown`, and
// one whose row was made again meanwhile (a sign-out) is `reset`.
private fun wrote(
    before: Long?,
    after: Long?,
    ran: Ran,
): String {
    val moved = generationMoved(before, after)
    val passed = ran.passed?.let { "${it.inserted}/${it.updated}" } ?: NONE
    val reconciled =
        ran.reconciled?.let {
            "${it.tombstoned}/${it.revived}/${it.fetched}/${it.seriesTombstoned}/${it.seriesRevived}"
        } ?: NONE
    val collections = ran.collections?.let { "${it.inserted}/${it.changed}/${it.revived}/${it.tombstoned}" } ?: NONE
    return "StayCurrentWrote generation=$moved incremental=$passed reconcile=$reconciled collections=$collections"
}

private const val NONE = "none"

// How many times the generation moved between [before] and [after], for a log line: `unknown` when either could not
// be read, `reset` when the row was made again meanwhile (a sign-out).
private fun generationMoved(
    before: Long?,
    after: Long?,
): String =
    when {
        before == null || after == null -> "unknown"
        after < before -> "reset"
        else -> (after - before).toString()
    }

// The outcome as a log field: the class name, and for a failure the SyncFailure name; never a message.
private fun SyncOutcome.label() =
    when (this) {
        SyncOutcome.Completed -> "outcome=Completed"
        SyncOutcome.Cancelled -> "outcome=Cancelled"
        is SyncOutcome.Failed -> "outcome=Failed failure=${failure.name}"
    }

// An Error that escaped a pass as a log field: the class name only, never a message.
private fun Throwable.label() = "outcome=Error error=${this::class.java.simpleName}"

// The reasons a run does nothing; the names are what the log line says.
private enum class Skip {
    EmptyPlan,
    Busy,
    NoServer,
    NoLibraries,
    NoAccount,
    FirstSyncOpen,
    SignedOut,
    SessionRejected,
    Unavailable,
    AccountChanged,
}

// Why a run did nothing: the plan, a reason name and at most a count or an enum name, never an ID, an address or a
// message. Always the Cancelled outcome, so a caller can return it.
private fun ((String) -> Unit).skipped(
    plan: SyncPlan,
    reason: Skip,
    detail: String = "",
): SyncOutcome {
    val extra = if (detail.isEmpty()) "" else " $detail"
    say("StayCurrentSkipped plan=${plan.incremental}/${plan.reconcile} reason=${reason.name}$extra")
    return SyncOutcome.Cancelled
}

// Runs the passes and says how they ended when they did not return: stopped or signed out is Cancelled, anything else
// is the server failure [StayCurrentRunner.safely] records, and an Error (an OutOfMemoryError, say) is Error with its
// class name. The throwable goes on; the line has no message.
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
private suspend fun ((String) -> Unit).timed(
    began: TimeMark,
    block: suspend () -> Ran,
): Ran {
    val done = { label: String -> sayEscaping("StayCurrentDone ms=${began.elapsedNow().inWholeMilliseconds} $label") }
    try {
        return block()
    } catch (stopped: CancellationException) {
        done("outcome=Cancelled")
        throw stopped
    } catch (signedOut: AccountNotActiveException) {
        done("outcome=Cancelled")
        throw signedOut
    } catch (unexpected: Exception) {
        done("outcome=Failed failure=${SyncFailure.Server.name}")
        throw unexpected
    } catch (fatal: Throwable) {
        done(fatal.label())
        throw fatal
    }
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

// The account's content generation for the log only: null when it cannot be read, so a logging read never fails the
// run.
@Suppress("TooGenericExceptionCaught")
private suspend fun RoomLibraryRepository.generationOrNull(account: LibraryAccount): Long? =
    try {
        contentGeneration(account)
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

// The same for a line told while an Error is escaping: under an OutOfMemoryError the log call may fail too, and that
// failure must not replace the Error.
@Suppress("TooGenericExceptionCaught")
private fun ((String) -> Unit).sayEscaping(line: String) {
    try {
        invoke(line)
    } catch (_: Throwable) {
        // Logging is best effort, and the Error already escaping is the one to report.
    }
}
