// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tv.stationbreak.core.domain.AccountCache
import tv.stationbreak.core.domain.LibraryRepository
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
    ): RoomLibraryRepository = RoomLibraryRepository.create(context, Clock.System)

    @Provides
    fun libraryRepository(repository: RoomLibraryRepository): LibraryRepository = repository

    @Provides
    fun accountCache(repository: RoomLibraryRepository): AccountCache = repository
}
