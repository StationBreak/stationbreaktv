// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tv.stationbreak.app.BuildConfig
import tv.stationbreak.feature.guide.GuideMeasured

/** The guide's switches that depend on the build (spec 17). */
@Module
@InstallIn(SingletonComponent::class)
internal object GuideModule {
    /** True in the debug and benchmark builds, which write the guide's measurement line; false in release. */
    @Provides
    @GuideMeasured
    fun guideMeasured(): Boolean = BuildConfig.GUIDE_MEASURE
}
