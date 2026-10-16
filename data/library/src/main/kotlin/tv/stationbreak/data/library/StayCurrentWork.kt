// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import tv.stationbreak.core.domain.LibraryRepository
import java.util.concurrent.TimeUnit

/** The part of WorkManager the periodic stay-current job uses, so a test can stand in for it. */
internal interface PeriodicEnqueuer {
    fun enqueueUniquePeriodic(
        name: String,
        policy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    )
}

/**
 * The work that keeps the saved library current after the first sync (spec 06, Unit 4): a one-time piece of work an app
 * open starts, and a unique periodic job about every 6 hours that runs both passes. Both need a network connection and
 * carry only two yes/no flags (which passes to run): the worker reads the session from the app's session store when it
 * runs, so no token is ever written to WorkManager's own database (Security: Tokens).
 */
object StayCurrentWork {
    /** The unique name of the on-open work: an open during a sync that is queued or running starts nothing. */
    const val OPEN_NAME = "stationbreak.stay-current.open"

    /** The unique name of the periodic job. */
    const val PERIODIC_NAME = "stationbreak.stay-current.periodic"

    /** Input flag: run the incremental pass. */
    const val KEY_INCREMENTAL = "incremental"

    /** Input flag: run the ID reconciliation. */
    const val KEY_RECONCILE = "reconcile"

    /** How often the periodic job runs, in hours. Android may delay it while the TV sleeps. */
    const val PERIOD_HOURS = 6L

    private val needsNetwork = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun flags(plan: SyncPlan): Data =
        Data
            .Builder()
            .putBoolean(KEY_INCREMENTAL, plan.incremental)
            .putBoolean(KEY_RECONCILE, plan.reconcile)
            .build()

    /** A request for the on-open work running [plan]. */
    fun openRequest(plan: SyncPlan): OneTimeWorkRequest =
        OneTimeWorkRequest
            .Builder(StayCurrentWorker::class.java)
            .setConstraints(needsNetwork)
            .setInputData(flags(plan))
            .build()

    /** A request for the periodic job: both passes, every [PERIOD_HOURS] hours, with a network connection. */
    fun periodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequest
            .Builder(StayCurrentWorker::class.java, PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(needsNetwork)
            .setInputData(flags(SyncPlan.Both))
            .build()
}

/** Gives the quiet worker what it needs to run. The app's `Application` implements it; there is no other way in. */
interface StayCurrentProvider {
    fun stayCurrent(): StayCurrent
}

/**
 * Runs the stay-current passes when WorkManager starts them. The work itself is [StayCurrentRunner]. It always reports
 * success: a failure is recorded in the saved library, and the next open or periodic run is the retry (a retrying
 * one-time job would also keep the unique name busy, so later opens would start nothing). With no
 * [StayCurrentProvider] (an `Application` that does not supply one) it stops: it has nothing to run.
 */
class StayCurrentWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): ListenableWorker.Result {
        val provider = applicationContext as? StayCurrentProvider ?: return Result.failure()
        val plan =
            SyncPlan(
                incremental = inputData.getBoolean(StayCurrentWork.KEY_INCREMENTAL, false),
                reconcile = inputData.getBoolean(StayCurrentWork.KEY_RECONCILE, false),
            )
        provider.stayCurrent().runner.run(plan)
        return Result.success()
    }
}

/**
 * Cancels the sync work when the account changes (see [SyncControl]): the first sync and the on-open stay-current work.
 * The periodic job stays; a run with nobody signed in does nothing.
 */
internal class AccountChangeSyncControl(
    private val enqueuer: WorkEnqueuer,
) : SyncControl {
    override fun cancelFirstSync() {
        enqueuer.cancelUnique(FirstSyncWork.UNIQUE_NAME)
        enqueuer.cancelUnique(StayCurrentWork.OPEN_NAME)
    }
}

/**
 * Keeps the saved library current (spec 06 task 4.7): `app` calls [onOpen] when the app is opened. It makes sure the
 * periodic job exists, asks the [SyncTriggerPolicy] what the open should start, and queues that as unique on-open work
 * that keeps any already queued or running (an open during a sync starts nothing). Quiet: it shows nothing.
 */
class StayCurrent internal constructor(
    private val enqueuer: WorkEnqueuer,
    private val periodic: PeriodicEnqueuer,
    private val library: LibraryRepository,
    private val policy: SyncTriggerPolicy,
    /** What the worker runs. The same object serves every trigger, so only one run goes at a time. */
    val runner: StayCurrentRunner,
) {
    /** The app was opened: schedule the periodic job if it is not, and start whatever the open calls for. */
    suspend fun onOpen() {
        schedulePeriodic()
        val plan = policy.onOpen(library.activeStatus().first(), runner.isRunning)
        if (plan.isEmpty) return
        // Appended, not kept: a Both plan must not be dropped behind a queued reconcile-only one. The runner's mutex
        // serialises the work, and the policy already starts nothing while a sync is running.
        val request = StayCurrentWork.openRequest(plan)
        enqueuer.enqueueUnique(StayCurrentWork.OPEN_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Makes sure the unique periodic job exists. An existing one is kept, so this may be called every time. */
    fun schedulePeriodic() {
        periodic.enqueueUniquePeriodic(
            StayCurrentWork.PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            StayCurrentWork.periodicRequest(),
        )
    }

    companion object {
        /**
         * Stay-current on the app's WorkManager, over the very stores [firstSync] reads (the stored server, the ticked
         * libraries, the session, the server and the saved library), so the two cannot disagree about whose it is.
         */
        fun create(
            context: Context,
            firstSync: FirstSyncRunner,
        ): StayCurrent {
            val enqueuer = WorkManagerEnqueuer(context)
            val runner =
                StayCurrentRunner(
                    firstSync.settings,
                    firstSync.sessions,
                    firstSync.server,
                    firstSync.repository,
                    firstSync.clock,
                )
            val policy = SyncTriggerPolicy(firstSync.clock)
            return StayCurrent(enqueuer, enqueuer, firstSync.repository, policy, runner)
        }
    }
}
