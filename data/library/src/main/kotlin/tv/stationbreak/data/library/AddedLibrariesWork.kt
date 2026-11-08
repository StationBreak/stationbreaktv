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
import androidx.work.WorkerParameters
import tv.stationbreak.core.domain.selection.LibraryPasses
import java.util.concurrent.TimeUnit

/**
 * The add pass as WorkManager work (spec 26, Part 4 §2): one unique piece of work, so one pass runs at a time, that
 * needs a network connection and, when it asks to be retried, waits longer each time. It carries no input data: the
 * worker reads the adds, the stored server and the session from the app's own stores when it runs, so no token is
 * ever written to WorkManager's own database (Security: Tokens).
 */
object AddedLibrariesWork {
    /** The unique name of the add work. */
    const val UNIQUE_NAME = "stationbreak.add-libraries"

    /** The first wait before a retry; WorkManager doubles it each time (up to its own cap of five hours). */
    const val BACKOFF_SECONDS = 30L

    /** A new request for the add work. */
    fun request(): OneTimeWorkRequest =
        OneTimeWorkRequest
            .Builder(AddedLibrariesWorker::class.java)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
}

/** Gives the add worker what it runs. The app's `Application` implements it; there is no other way in. */
interface AddedLibrariesRunnerProvider {
    fun addedLibrariesRunner(): AddedLibrariesRunner
}

/**
 * Runs the add pass when WorkManager starts it. The work itself is [AddedLibrariesRunner]; this only maps its verdict.
 * With no [AddedLibrariesRunnerProvider] (an `Application` that does not supply one) it stops: it has nothing to run.
 */
class AddedLibrariesWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): ListenableWorker.Result {
        val provider = applicationContext as? AddedLibrariesRunnerProvider ?: return Result.failure()
        return when (provider.addedLibrariesRunner().run(runAttemptCount)) {
            SyncVerdict.Success -> Result.success()
            SyncVerdict.Retry -> Result.retry()
            SyncVerdict.Failure -> Result.failure()
        }
    }
}

/**
 * The selection's [LibraryPasses] over WorkManager (spec 26, Part 4 §2): [restart] only enqueues, replacing any add
 * work queued or running, so the pass that runs reads the adds as they are stored now. It never waits for the work.
 */
class WorkManagerLibraryPasses internal constructor(
    private val enqueuer: WorkEnqueuer,
) : LibraryPasses {
    override fun restart() {
        enqueuer.enqueueUnique(AddedLibrariesWork.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, AddedLibrariesWork.request())
    }

    companion object {
        /** The add pass on the app's WorkManager. Building it starts nothing. */
        fun create(context: Context): LibraryPasses = WorkManagerLibraryPasses(WorkManagerEnqueuer(context))
    }
}
