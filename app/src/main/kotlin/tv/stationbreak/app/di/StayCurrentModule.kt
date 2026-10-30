// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import android.content.Context
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tv.stationbreak.app.StationBreakApp
import tv.stationbreak.data.library.FirstSyncRunner
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
     * cannot disagree about whose library it is.
     */
    @Provides
    @Singleton
    fun stayCurrent(
        @ApplicationContext context: Context,
        firstSync: FirstSyncRunner,
    ): StayCurrent = StayCurrent.create(context, firstSync) { Log.i(SYNC_TAG, it) }
}
