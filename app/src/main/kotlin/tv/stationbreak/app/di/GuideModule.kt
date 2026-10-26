// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tv.stationbreak.app.R
import tv.stationbreak.feature.guide.GuideMeasured

/** The guide's switches that depend on the build (spec 17). */
@Module
@InstallIn(SingletonComponent::class)
internal object GuideModule {
    /** True in the debug and benchmark builds, which write the guide's measurement line; false in release. */
    @Provides
    @GuideMeasured
    fun guideMeasured(
        @ApplicationContext context: Context,
    ): Boolean = context.resources.getBoolean(R.bool.guide_measure)
}
