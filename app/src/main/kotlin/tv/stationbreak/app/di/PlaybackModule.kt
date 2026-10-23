// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.playback.PlaybackResolver
import tv.stationbreak.core.playback.probe.AndroidCodecInventory
import tv.stationbreak.core.playback.probe.CapabilityProbe
import tv.stationbreak.core.playback.probe.DeviceCapabilitiesCache
import javax.inject.Singleton

/**
 * The bindings for playback (spec 13, ADR-0016): the device's capabilities and the resolver that decides how to play an
 * item. The player itself is in [PlayerModule], so a test that replaces the HTTP client can replace the player too.
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

    @Provides
    fun playbackResolver(
        server: MediaServer,
        capabilities: DeviceCapabilitiesCache,
    ): PlaybackResolver = PlaybackResolver(server, capabilities::get)
}
