// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tv.stationbreak.app.BuildConfig
import tv.stationbreak.core.playback.WarmConfig
import tv.stationbreak.core.playback.WarmSwitches
import javax.inject.Singleton

/**
 * The warm standby's setup (spec 24), provided once so the player and the tune controller get the same instances and
 * can never disagree about whether warming is on. Apart from [PlayerModule], so a test that replaces the player
 * keeps it.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object WarmModule {
    private val setup: WarmSetup by lazy {
        warmSetupFor(debuggable = BuildConfig.DEBUG, benchmark = BuildConfig.BUILD_TYPE == "benchmark")
    }

    @Provides
    @Singleton
    fun warmConfig(): WarmConfig = setup.config

    @Provides
    @Singleton
    fun warmSwitches(): WarmSwitches = setup.switches
}
