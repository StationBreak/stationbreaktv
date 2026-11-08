// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.delay
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.RestoreOutcome
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.StoredLibrary
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.selection.AddLandings
import tv.stationbreak.core.domain.selection.LandResult
import tv.stationbreak.core.domain.selection.SelectionReady
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.Session
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The add pass's tunables (spec 26, Part 4 §2). [landRetries]: how many times a landing that may succeed later
 * ([LandResult.Retry]) is tried again for one stamp, in the life of the process. [firstRetryWait]: the wait before the
 * first of those; each one after waits twice as long. [unprovenWalks]: how many times one run walks an add again after
 * the selection found no proof of its walk ([LandResult.Unproven]); those are not retries.
 */
data class AddPassConfig(
    val landRetries: Int = 5,
    val firstRetryWait: Duration = 2.seconds,
    val unprovenWalks: Int = 2,
) {
    init {
        require(landRetries >= 0 && unprovenWalks >= 0) { "landRetries and unprovenWalks must not be negative" }
    }
}

/**
 * What the add worker runs (spec 26, Part 4 §2 and §4): the libraries being added, each walked under its add stamp
 * ([LibraryPass]) and proved by the walk's own last write, then handed to the selection to land
 * ([AddLandings.passLanded]). It never lands anything itself, and it writes nothing outside the guarded writes, so a
 * library unticked, held or ticked again meanwhile takes nothing from it.
 *
 * - It waits for the selection's resume at start ([ready]) before it reads the adds, then reads them again after each
 *   one, so an add ticked while it runs is read in this run. What it calls on the selection never asks for the add
 *   pass again (that would replace this very worker).
 * - A walk the cache refused while the settings still name the stamp (or with no row at all) asks the selection to
 *   put the cache in line ([AddLandings.reconcile]), once per stamp and run, and then reads that add again.
 * - [LandResult.Retry] is tried again after a wait that doubles each time, at most [AddPassConfig.landRetries] times
 *   per stamp in the life of the process; then the stamp is given up (`AddLandingStuck`) until the next start, whose
 *   resume asks for the pass again. [LandResult.Failed] is given up at once (`AddLandingFailed`).
 *   [LandResult.Unproven] walks the add again (from its checkpoint): that is no retry.
 * - It stays out of the sync signal the lineup refresher holds its triggers on (D9): an add moves no day until it
 *   lands, and the landing is the selection's, which publishes it.
 *
 * Like the other workers it reads the stored server and the session from the app's own stores when it runs, so no
 * token passes through WorkManager. Its report lines are names and counts only.
 */
@Suppress("LongParameterList") // The stores the other workers read, the selection's two ports, and its tunables.
class AddedLibrariesRunner(
    private val settings: ServerSettings,
    private val sessions: SessionRepository,
    private val server: MediaServer,
    private val repository: RoomLibraryRepository,
    private val landings: AddLandings,
    private val ready: SelectionReady,
    private val clock: Clock = Clock.System,
    private val config: AddPassConfig = AddPassConfig(),
    private val report: (String) -> Unit = {},
) {
    // The landing retries of each stamp, for the life of the process; past the cap a stamp is given up.
    private val retries = ConcurrentHashMap<AddKey, Int>()

    /** Test seam: the wait between landing retries. A plain delay in the app. */
    internal var sleep: suspend (Duration) -> Unit = { delay(it) }

    /** Runs the add pass once and says what WorkManager should do next. [attempt] is the run's retry count. */
    suspend fun run(attempt: Int): SyncVerdict = verdict(outcome(), attempt)

    @Suppress("ReturnCount") // Each early return is a reason there is nothing to add.
    internal suspend fun outcome(): SyncOutcome {
        // The selection's resume at start comes first (spec 26): it may still be putting the library cache in line.
        ready.await()
        val record = settings.load() ?: return SyncOutcome.Cancelled
        if (settings.loadSelection()?.adding.isNullOrEmpty()) return SyncOutcome.Completed
        return when (val restored = sessions.verify(record.address)) {
            is RestoreOutcome.Restored -> {
                val session = restored.session
                val account = LibraryAccount(session.server.id, session.userId)
                // Signed out (or another account in) while the server was answering: nothing to add.
                val active = ActiveAccountEntity(account.server.value, account.user.value)
                if (repository.dao.activeAccounts() != listOf(active)) {
                    SyncOutcome.Cancelled
                } else {
                    try {
                        Pass(session, account).run()
                    } catch (_: AccountNotActiveException) {
                        SyncOutcome.Cancelled
                    }
                }
            }

            RestoreOutcome.SignedOut, is RestoreOutcome.Rejected -> {
                SyncOutcome.Failed(SyncFailure.SessionExpired)
            }

            is RestoreOutcome.Unavailable -> {
                SyncOutcome.Failed(restored.error.toSyncFailure())
            }
        }
    }

    // One add, by its stamp: a newer tick of the same library is another add.
    private data class AddKey(
        val account: LibraryAccount,
        val id: ItemId,
        val stamp: Long,
    )

    // What one add came to: on to the next, the same add read again (after a reconcile), or the run's end.
    private sealed interface Step {
        data object Next : Step

        data object Again : Step

        class Stop(
            val outcome: SyncOutcome,
        ) : Step
    }

    @Suppress("ReturnCount") // Each early return is where an add stops.
    private inner class Pass(
        private val session: Session,
        private val account: LibraryAccount,
    ) {
        private val handled = HashSet<AddKey>()
        private val reconciled = HashSet<AddKey>()
        private var listed: Map<ItemId, Library>? = null
        private var landed = 0

        suspend fun run(): SyncOutcome {
            while (true) {
                val stored = settings.loadSelection() ?: return done()
                // Another account's choice (a clear that failed) is not this account's to add to.
                if (stored.owner != null && stored.owner != account) return SyncOutcome.Cancelled
                val next =
                    stored.adding.entries
                        .sortedBy { it.key.value }
                        .map { AddKey(account, it.key, it.value) }
                        .firstOrNull { it !in handled } ?: return done()
                handled += next
                when (val step = add(next)) {
                    Step.Next -> Unit
                    Step.Again -> handled -= next
                    is Step.Stop -> return step.outcome
                }
            }
        }

        private fun done(): SyncOutcome {
            report("AddPassDone landed=$landed")
            return SyncOutcome.Completed
        }

        private suspend fun add(key: AddKey): Step {
            // A stamp given up earlier in this process waits for the next start.
            if ((retries[key] ?: 0) > config.landRetries) return Step.Next
            val library =
                when (val known = library(key.id)) {
                    is Listed.Failed -> return Step.Stop(SyncOutcome.Failed(known.failure))

                    // Not listed: the selection's listings decide what becomes of it (the two-read rule).
                    Listed.Missing -> return Step.Next

                    is Listed.Found -> known.library
                }
            return add(key, library)
        }

        // Walks (when it is not proved yet) and lands one listed add, until it is done.
        private suspend fun add(
            key: AddKey,
            library: Library,
        ): Step {
            var walkAgain = false
            var unproven = 0
            while (true) {
                val row = rowOf(key.id)?.takeIf { it.takes(key) } ?: return mismatch(key, rowOf(key.id))
                if (walkAgain || row.walkedStamp != key.stamp) walk(key, library)?.let { return it }
                walkAgain =
                    when (val landing = land(key)) {
                        Landing.WalkAgain -> {
                            if (++unproven > config.unprovenWalks) return given("AddLandingUnproven")
                            true
                        }

                        Landing.TryAgain -> {
                            false
                        }

                        is Landing.Done -> {
                            return landing.step
                        }
                    }
            }
        }

        // Whether this row takes the add's writes: its stamp, and no hold.
        private fun StoredLibrary.takes(key: AddKey): Boolean = addStamp == key.stamp && heldUntilMs == null

        private fun given(line: String): Step {
            report(line)
            return Step.Next
        }

        // Hands a proved add to the selection: done, walked again, or tried again after its wait.
        private suspend fun land(key: AddKey): Landing =
            when (landings.passLanded(account, key.id, key.stamp)) {
                LandResult.Landed -> {
                    landed++
                    Landing.Done(Step.Next)
                }

                LandResult.NotCurrent -> {
                    Landing.Done(Step.Next)
                }

                // No proof in the cache: read it again from its checkpoint. That is no retry.
                LandResult.Unproven -> {
                    Landing.WalkAgain
                }

                LandResult.Retry -> {
                    val tries = retries.merge(key, 1, Int::plus) ?: 1
                    if (tries > config.landRetries) {
                        Landing.Done(given("AddLandingStuck"))
                    } else {
                        sleep(config.firstRetryWait * (1 shl (tries - 1)))
                        Landing.TryAgain
                    }
                }

                LandResult.Failed -> {
                    retries[key] = config.landRetries + 1
                    Landing.Done(given("AddLandingFailed"))
                }
            }

        // Walks [key]'s library under its stamp and proves it: null when proved, else what the add comes to.
        private suspend fun walk(
            key: AddKey,
            library: Library,
        ): Step? {
            val pass =
                LibraryPass(
                    repository,
                    server,
                    session,
                    account,
                    key.stamp,
                    false,
                    MediaServer.MAX_PAGE_SIZE,
                    FirstSyncEngine.DEFAULT_MARGIN,
                    SkipBudget(FirstSyncEngine.MAX_SKIPPED_ITEMS),
                    report,
                )
            return when (val end = pass.walk(library, null, clock.now())) {
                // The proof, with the walk's coverage, in the walk's own last guarded write.
                is PassEnd.Walked -> {
                    val proved = repository.markAddWalked(account, key.id, key.stamp, end.run)
                    if (proved) null else mismatch(key, rowOf(key.id))
                }

                is PassEnd.Refused -> {
                    mismatch(key, rowOf(key.id))
                }

                is PassEnd.Failed -> {
                    Step.Stop(SyncOutcome.Failed(end.error.toSyncFailure()))
                }
            }
        }

        // The cache refused the add's stamp. When the settings still name it, or the row is gone, the cache is put in
        // line once and the add read again; otherwise the add moved on (a newer tick, an untick) and so does this.
        private suspend fun mismatch(
            key: AddKey,
            row: StoredLibrary?,
        ): Step {
            val named = settings.loadSelection()?.adding?.get(key.id) == key.stamp
            if ((named || row == null) && reconciled.add(key)) {
                report("AddReconcile")
                landings.reconcile()
                return Step.Again
            }
            return Step.Next
        }

        private suspend fun rowOf(id: ItemId): StoredLibrary? {
            val rows = repository.storedLibraries(account)
            return rows.firstOrNull { it.id == id }
        }

        // The server's library [id], from one listing per run.
        private suspend fun library(id: ItemId): Listed {
            val known =
                listed ?: when (val result = server.libraries(session)) {
                    is ServerResult.Success -> result.value.associateBy { it.id }.also { listed = it }
                    is ServerResult.Failure -> return Listed.Failed(result.error.toSyncFailure())
                }
            return known[id]?.let { Listed.Found(it) } ?: Listed.Missing
        }
    }

    // What a landing came to, for the add's loop.
    private sealed interface Landing {
        data object WalkAgain : Landing

        data object TryAgain : Landing

        class Done(
            val step: Step,
        ) : Landing
    }

    private sealed interface Listed {
        class Found(
            val library: Library,
        ) : Listed

        data object Missing : Listed

        class Failed(
            val failure: SyncFailure,
        ) : Listed
    }

    internal companion object {
        /**
         * What WorkManager does next: nothing to add, or done, is done; a network failure retries with its growing
         * waits; a rejected session stops (signing in again restarts the pass); a server failure retries a few times.
         */
        fun verdict(
            outcome: SyncOutcome,
            attempt: Int,
        ): SyncVerdict =
            when (outcome) {
                SyncOutcome.Completed, SyncOutcome.Cancelled -> SyncVerdict.Success
                is SyncOutcome.Failed -> SyncRetryPolicy.verdict(outcome, attempt)
            }
    }
}
