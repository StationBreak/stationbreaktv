// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tv.stationbreak.app.BuildConfig
import tv.stationbreak.core.domain.ProEntitlement
import javax.inject.Singleton

/**
 * Whether StationBreak Pro is unlocked (#793): the build's `PRO_UNLOCKED` for now (see app/build.gradle.kts), so a
 * release build ships with the free-tier gates closed. When billing arrives (#36), this becomes "build unlock OR store
 * entitlement": it wraps a flavor-bound store source and is not replaced, so debug builds stay Pro without a purchase.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object ProModule {
    @Provides
    @Singleton
    fun proEntitlement(): ProEntitlement = BuildUnlock(BuildConfig.PRO_UNLOCKED)
}

/** An entitlement fixed by the build. */
internal class BuildUnlock(
    unlocked: Boolean,
) : ProEntitlement {
    override val pro: StateFlow<Boolean> = MutableStateFlow(unlocked)
}
