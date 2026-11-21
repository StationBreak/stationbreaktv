// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import tv.stationbreak.app.BuildConfig
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.ProEntitlement
import tv.stationbreak.core.domain.lineup.ProRevocation
import tv.stationbreak.core.domain.store.ProCache
import tv.stationbreak.core.domain.store.StoreBackedPro
import tv.stationbreak.core.domain.store.StoreEntitlement
import tv.stationbreak.data.settings.pro.DataStoreProCache
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether StationBreak Pro is unlocked (#793, #99, ADR-0032): the build's `PRO_UNLOCKED` (see app/build.gradle.kts) OR
 * the store's verified entitlement. The store is bound per flavor: the Amazon release's adapter in `src/amazon`, none
 * (never owned) in `src/github`, so the GitHub build carries no billing code. A build that unlocks Pro never reads it.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object ProModule {
    @Provides
    @Singleton
    fun proCache(
        @ApplicationContext context: Context,
    ): ProCache = DataStoreProCache.create(context)

    @Provides
    @Singleton
    fun proEntitlement(
        store: StoreEntitlement,
        cache: ProCache,
        @AppScope scope: CoroutineScope,
    ): ProEntitlement = StoreBackedPro(BuildConfig.PRO_UNLOCKED, store, cache, scope)
}

/** Starts [ProRevocation] on the app scope: starters return to defaults when the store verifies a refund. */
internal class ProRevocationWatch
    @Inject
    constructor(
        private val revocation: ProRevocation,
        private val pro: ProEntitlement,
        private val store: StoreEntitlement,
        private val library: LibraryRepository,
    ) {
        fun start(scope: CoroutineScope) = revocation.watch(scope, pro, store, library)
    }
