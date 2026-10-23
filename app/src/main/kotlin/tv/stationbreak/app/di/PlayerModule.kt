// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.playback.Media3VideoPlayerFactory
import tv.stationbreak.core.playback.VideoPlayerFactory
import tv.stationbreak.data.jellyfin.JellyfinMediaServer
import tv.stationbreak.data.jellyfin.mediaCallFactory

/**
 * The player's own bindings (spec 13, ADR-0016): the data source it reads media through and the factory that builds it.
 * A screen that plays creates one player from the [VideoPlayerFactory] for its lifetime and releases it; it never names
 * a Media3 type. Kept apart from [PlaybackModule] so that a test which replaces the HTTP client, and so cannot build
 * the real data source, can replace just the player.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object PlayerModule {
    /**
     * The player's way to read media: the credentialed media client behind Media3's OkHttp data source, handed to the
     * player as it is. It is never wrapped in `DefaultDataSource.Factory`, which would open `file://` and `content://`
     * URIs a server's playlist names (ADR-0016, 9). The call factory asks [SessionRepository.current] for every
     * request. It reads the device ID with `runBlocking` on its first call, which is Media3's loader thread, never the
     * main thread.
     */
    @Provides
    @MediaDataSource
    @UnstableApi
    fun mediaDataSource(
        server: JellyfinMediaServer,
        sessions: SessionRepository,
    ): DataSource.Factory = OkHttpDataSource.Factory(server.mediaCallFactory { sessions.current.value })

    /** The way a screen gets its player: Media3 behind it, the media client's data source inside it. */
    @Provides
    @UnstableApi
    fun videoPlayerFactory(
        @MediaDataSource dataSource: DataSource.Factory,
    ): VideoPlayerFactory = Media3VideoPlayerFactory(dataSource)
}
