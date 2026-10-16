// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/**
 * The very first sync of the signed-in account, as launch routing and the progress screen see it (spec 06 task 3.0,
 * owner answer 3). It is a port so `feature:onboarding` never sees WorkManager or Room: `data:library` implements it and
 * `app` binds it. No token, URL or account name crosses it.
 */
interface FirstSync {
    /**
     * The state of the signed-in account's saved library, as a flow that emits the current value first, or null while
     * nobody is signed in (then there is nothing to wait for).
     */
    fun status(): Flow<LibraryStatus?>

    /**
     * Makes sure the first sync is queued or running. Does nothing if it already is, so the screen may call it every
     * time it appears.
     */
    fun ensureRunning()

    /** Runs the first sync again now, after it stopped. A sync that is still waiting to retry is started at once. */
    fun retry()

    companion object {
        /** Nobody is signed in and nothing is syncing: the first sync counts as done. */
        val Finished: FirstSync =
            object : FirstSync {
                override fun status(): Flow<LibraryStatus?> = flowOf(null)

                override fun ensureRunning() = Unit

                override fun retry() = Unit
            }
    }
}

/**
 * The first sync has ended when the account has a last-success time, or nobody is signed in. A sync that fails later
 * does not bring the progress screen back: only a library that has never been saved completely is "unfinished".
 */
suspend fun FirstSync.hasFinished(): Boolean {
    val status = status().first() ?: return true
    return status.lastSuccess != null
}
