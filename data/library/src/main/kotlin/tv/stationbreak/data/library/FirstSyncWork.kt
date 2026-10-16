// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.Flow
import tv.stationbreak.core.domain.FirstSync
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.LibraryStatus
import java.util.concurrent.TimeUnit

/**
 * The first sync as WorkManager work (spec 06 task 3.6): one unique piece of work, so only one sync runs at a time and
 * a second start does nothing, that needs a network connection and, when it asks to be retried, waits longer each time.
 * It carries no input data: the worker reads the session from the app's session store when it runs, so no token is
 * ever written to WorkManager's own database (Security: Tokens).
 */
object FirstSyncWork {
    /** The unique name of the first-sync work. */
    const val UNIQUE_NAME = "stationbreak.first-sync"

    /** The first wait before a retry; WorkManager doubles it each time (up to its own cap of five hours). */
    const val BACKOFF_SECONDS = 30L

    /** A new request for the first-sync work. */
    fun request(): OneTimeWorkRequest =
        OneTimeWorkRequest
            .Builder(FirstSyncWorker::class.java)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
}

/** Gives the worker what it needs to run. The app's `Application` implements it; there is no other way in. */
interface FirstSyncRunnerProvider {
    fun firstSyncRunner(): FirstSyncRunner
}

/**
 * Runs the first sync when WorkManager starts it. The work itself is [FirstSyncRunner]; this only maps its verdict.
 * With no [FirstSyncRunnerProvider] (an `Application` that does not supply one) it stops: it has nothing to run.
 */
class FirstSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): ListenableWorker.Result {
        val provider = applicationContext as? FirstSyncRunnerProvider ?: return Result.failure()
        return when (provider.firstSyncRunner().run(runAttemptCount)) {
            SyncVerdict.Success -> Result.success()
            SyncVerdict.Retry -> Result.retry()
            SyncVerdict.Failure -> Result.failure()
        }
    }
}

/** The part of WorkManager the first sync uses, so a test can stand in for it. */
internal interface WorkEnqueuer {
    fun enqueueUnique(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    )

    fun cancelUnique(name: String)
}

/** WorkManager itself. It is asked for on first use, so building this starts nothing. */
internal class WorkManagerEnqueuer(
    context: Context,
) : WorkEnqueuer {
    private val appContext = context.applicationContext
    private val manager: WorkManager by lazy { WorkManager.getInstance(appContext) }

    override fun enqueueUnique(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        manager.enqueueUniqueWork(name, policy, request)
    }

    override fun cancelUnique(name: String) {
        manager.cancelUniqueWork(name)
    }
}

/** Cancels the first-sync work when the account changes (see [SyncControl]). */
internal class WorkManagerSyncControl(
    private val enqueuer: WorkEnqueuer,
) : SyncControl {
    override fun cancelFirstSync() = enqueuer.cancelUnique(FirstSyncWork.UNIQUE_NAME)
}

/**
 * The [FirstSync] port over WorkManager. [ensureRunning] keeps work that is already queued or running, so the screen
 * may call it every time it shows; [retry] replaces the work, so a sync that is waiting out a backoff starts now.
 */
class WorkManagerFirstSync internal constructor(
    private val enqueuer: WorkEnqueuer,
    private val library: LibraryRepository,
) : FirstSync {
    override fun status(): Flow<LibraryStatus?> = library.activeStatus()

    override fun ensureRunning() = start(ExistingWorkPolicy.KEEP)

    override fun retry() = start(ExistingWorkPolicy.REPLACE)

    private fun start(policy: ExistingWorkPolicy) = enqueuer.enqueueUnique(FirstSyncWork.UNIQUE_NAME, policy, FirstSyncWork.request())

    companion object {
        /** The first sync on the app's WorkManager, reading its state from [library]. */
        fun create(
            context: Context,
            library: LibraryRepository,
        ): FirstSync = WorkManagerFirstSync(WorkManagerEnqueuer(context), library)
    }
}
