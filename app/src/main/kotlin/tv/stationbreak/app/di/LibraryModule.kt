// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import android.content.Context
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tv.stationbreak.core.domain.AccountCache
import tv.stationbreak.core.domain.AccountCaches
import tv.stationbreak.core.domain.DayStore
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.data.library.CollectionRelist
import tv.stationbreak.data.library.HistoryRetention
import tv.stationbreak.data.library.RoomLibraryRepository
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * The saved library (F0.6, spec 06 Unit 2): the Room-backed repository behind the [LibraryRepository] port, and the
 * same object as the [AccountCache] the session repository tells about sign-in and sign-out. `core:*` holds no Room, so
 * the binding is here (MODULES.md, "Hilt in pure modules"). The database file opens on its first query, off the main
 * thread.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object LibraryModule {
    @Provides
    @Singleton
    fun roomLibraryRepository(
        @ApplicationContext context: Context,
    ): RoomLibraryRepository {
        val relist = CollectionRelist()
        // Numbers only (a hold the clock guard kept past its end, say). Logging is best effort.
        val report: (String) -> Unit = { runCatching { Log.i(SYNC_TAG, it) } }
        return RoomLibraryRepository.create(context, Clock.System, HistoryRetention(), relist, report)
    }

    @Provides
    fun libraryRepository(repository: RoomLibraryRepository): LibraryRepository = repository

    /**
     * What sign-in and sign-out tell: the saved library first, then the built days on disk made from it (#480), so a
     * signed-out or switched-away account's days go with its library.
     */
    @Provides
    fun accountCache(
        repository: RoomLibraryRepository,
        days: DayStore,
    ): AccountCache = AccountCaches(repository, days)

    private const val SYNC_TAG = "Sync"
}
