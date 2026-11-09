// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import android.content.Context
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tv.stationbreak.app.StationBreakApp
import tv.stationbreak.core.domain.SyncNow
import tv.stationbreak.core.domain.selection.HoldExpiry
import tv.stationbreak.core.domain.selection.LibraryChoices
import tv.stationbreak.core.domain.selection.ServerListings
import tv.stationbreak.data.library.FirstSyncRunner
import tv.stationbreak.data.library.RunnerSyncNow
import tv.stationbreak.data.library.StayCurrent
import javax.inject.Singleton

private const val SYNC_TAG = "Sync"

/**
 * The quiet stay-current (spec 06 Unit 4), owned by the graph (#480). It is apart from [LibraryModule] so a test that
 * replaces the saved library keeps this binding and need not copy it.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object StayCurrentModule {
    /**
     * The one stay-current for the process, so the open trigger, the on-open work and the periodic job share one runner
     * and its one gate. The `Application` and the worker both reach it through the graph (see
     * [StationBreakApp.FirstSyncEntryPoint]). It is built over the stores the first-sync runner reads, so the two
     * cannot disagree about whose library it is. After its passes it reaches the selection through two of its narrow
     * ports only: the expiry of ended holds and the following of the run's listing (spec 26, Task 4.5).
     */
    @Provides
    @Singleton
    fun stayCurrent(
        @ApplicationContext context: Context,
        firstSync: FirstSyncRunner,
        listings: ServerListings,
        holds: HoldExpiry,
    ): StayCurrent = StayCurrent.create(context, firstSync, listings, holds) { Log.i(SYNC_TAG, it) }

    /**
     * Sync now (spec 26, Task 5.1), over the one stay-current runner, so a press waits behind any other run and two
     * never run at once. Its one listing goes through the selection's [LibraryChoices]. One for the process, so its
     * state outlives the guide that shows it; presses run on a root scope of their own.
     */
    @Provides
    @Singleton
    @Suppress("InjectDispatcher") // A root scope for the presses; nothing to inject one into.
    fun syncNow(
        stayCurrent: StayCurrent,
        choices: LibraryChoices,
    ): SyncNow {
        val failed = CoroutineExceptionHandler { _, _ -> Log.w(SYNC_TAG, "SyncNowFailed") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + failed)
        return RunnerSyncNow(choices, stayCurrent.runner, scope) { Log.i(SYNC_TAG, it) }
    }
}
