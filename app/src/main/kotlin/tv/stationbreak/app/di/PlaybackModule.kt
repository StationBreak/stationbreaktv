// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.playback.PlaybackResolver
import tv.stationbreak.core.playback.probe.AndroidCodecInventory
import tv.stationbreak.core.playback.probe.CapabilityProbe
import tv.stationbreak.core.playback.probe.DeviceCapabilitiesCache
import tv.stationbreak.data.jellyfin.JellyfinMediaServer
import tv.stationbreak.data.jellyfin.mediaCallFactory
import javax.inject.Singleton

/**
 * The bindings for playback (spec 13, ADR-0016): the device's capabilities, the data source the player reads media
 * through, and the resolver that decides how to play an item. There is no player here: the screen that plays creates
 * one `Media3VideoPlayer` for its lifetime and releases it.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object PlaybackModule {
    /**
     * The capabilities, probed on first use and kept. An HDMI sink that is plugged in or out refreshes only the audio
     * part (`CapabilityProbe.withSink`). The broadcast is the platform's own: Media3's receiver type stays in
     * `core:playback`.
     */
    @Provides
    @Singleton
    @UnstableApi // The inventory reads Media3 audio capabilities, and its type is marked unstable.
    fun capabilities(
        @ApplicationContext context: Context,
    ): DeviceCapabilitiesCache {
        val probe = CapabilityProbe(AndroidCodecInventory(context))
        val cache = DeviceCapabilitiesCache(probe::probe, probe::withSink)
        // Registered for the life of the process, like the singleton it refreshes.
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    received: Context,
                    intent: Intent,
                ) = cache.refreshSink()
            }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return cache
    }

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

    @Provides
    fun playbackResolver(
        server: MediaServer,
        capabilities: DeviceCapabilitiesCache,
    ): PlaybackResolver = PlaybackResolver(server, capabilities::get)
}
