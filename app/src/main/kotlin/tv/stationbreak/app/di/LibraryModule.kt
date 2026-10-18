// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tv.stationbreak.core.domain.AccountCache
import tv.stationbreak.core.domain.FirstSync
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.data.library.CollectionRelist
import tv.stationbreak.data.library.FirstSyncRunner
import tv.stationbreak.data.library.HistoryRetention
import tv.stationbreak.data.library.RoomLibraryRepository
import tv.stationbreak.data.library.WorkManagerFirstSync
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
        return RoomLibraryRepository.create(context, Clock.System, HistoryRetention(), relist)
    }

    @Provides
    fun libraryRepository(repository: RoomLibraryRepository): LibraryRepository = repository

    @Provides
    fun accountCache(repository: RoomLibraryRepository): AccountCache = repository

    /**
     * The first sync of the signed-in account (spec 06, Unit 3) on WorkManager: the progress screen and launch routing
     * ask it for the saved library's status and to start or run again. `core:*` and the feature module never see
     * WorkManager (checkArchitecture).
     */
    @Provides
    @Singleton
    fun firstSync(
        @ApplicationContext context: Context,
        repository: RoomLibraryRepository,
    ): FirstSync = WorkManagerFirstSync.create(context, repository)

    /**
     * What the first-sync worker runs. WorkManager builds the worker itself, so the worker asks the `Application` for
     * this (see [FirstSyncEntryPoint]). It reads the session from the secret store when it runs: nothing, and no token,
     * is passed through WorkManager.
     */
    @Provides
    @Singleton
    fun firstSyncRunner(
        settings: ServerSettings,
        sessions: SessionRepository,
        server: MediaServer,
        repository: RoomLibraryRepository,
    ): FirstSyncRunner = FirstSyncRunner(settings, sessions, server, repository, Clock.System)
}
