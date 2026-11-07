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
    @Provides
    @Singleton
    fun warmSetup(): WarmSetup = warmSetupFor(switches = BuildConfig.WARM_SWITCHES)

    @Provides
    @Singleton
    fun warmConfig(setup: WarmSetup): WarmConfig = setup.config

    @Provides
    @Singleton
    fun warmSwitches(setup: WarmSetup): WarmSwitches = setup.switches
}
