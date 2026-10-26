// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.details.ProgramDetailsCache
import javax.inject.Singleton

/**
 * Program details fetched on demand (spec 17). Kept apart from [MediaServerModule] so a test graph that replaces the
 * media server still gets the cache, built on whichever [MediaServer] and [SessionRepository] that graph provides.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object DetailsModule {
    /**
     * Kept per account in memory. It runs its requests in its own supervised scope, so a screen that goes away never
     * cancels a request another screen is waiting on.
     */
    @Provides
    @Singleton
    @Suppress("InjectDispatcher") // The cache's own scope: a root, with nothing to inject a dispatcher into.
    fun programDetails(
        server: MediaServer,
        sessions: SessionRepository,
    ): ProgramDetailsCache {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return ProgramDetailsCache(server, sessions, scope)
    }
}
