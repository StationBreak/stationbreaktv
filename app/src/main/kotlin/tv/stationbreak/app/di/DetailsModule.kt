// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.details.LogoCache
import tv.stationbreak.core.domain.details.LogoStore
import tv.stationbreak.core.domain.details.ProgramDetailsCache
import tv.stationbreak.data.settings.logos.FileLogoStore
import java.io.File
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

    /**
     * Logos on disk, in the app's cache folder: capped (30 MB), per account, and Android may clear it whenever it
     * likes, which only costs a fetch. Nothing in it is a secret, and nothing in it is backed up.
     */
    @Provides
    @Singleton
    fun logoStore(
        @ApplicationContext context: Context,
    ): LogoStore = FileLogoStore(File(context.cacheDir, LOGO_FOLDER))

    /** Fetches a logo with the session's token through the media server's own client, and keeps it in [store]. */
    @Provides
    @Singleton
    @Suppress("InjectDispatcher") // The cache's own scope: a root, with nothing to inject a dispatcher into.
    fun logos(
        server: MediaServer,
        sessions: SessionRepository,
        store: LogoStore,
    ): LogoCache = LogoCache(server, sessions, store, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private const val LOGO_FOLDER = "logos"
}
