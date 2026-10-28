// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import android.content.Context
import android.view.ViewConfiguration
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tv.stationbreak.core.domain.LastChannelStore
import tv.stationbreak.data.settings.lastchannel.FileLastChannelStore
import tv.stationbreak.feature.live.LiveTimings
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the live channel screen (spec 14, ADR-0017) needs that no other module already binds: the file that remembers
 * the last channel watched. The screen's other collaborators (the schedule, the sessions, the library, the player's
 * factory, the clock) are bound by the lineup, media-server and playback modules.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object LiveModule {
    @Provides
    @Singleton
    fun lastChannelStore(
        @ApplicationContext context: Context,
    ): LastChannelStore = FileLastChannelStore(context)

    /**
     * The live screen's tunables (`LiveTimings`), built around this device's own key-repeat timeout, so a held flip
     * key's release fallback always outlasts the gap before its first auto-repeat (#430).
     */
    @Provides
    fun liveTimings(): LiveTimings {
        val keyRepeatTimeout = ViewConfiguration.getKeyRepeatTimeout().milliseconds
        return LiveTimings.forKeyRepeatTimeout(keyRepeatTimeout)
    }
}
