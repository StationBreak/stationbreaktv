// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tv.stationbreak.core.domain.store.PurchaseOutcome
import tv.stationbreak.core.domain.store.StoreEntitlement
import javax.inject.Singleton

/**
 * The GitHub flavor has no store (#99, Q-BIZ-1): it is GPL, ships with Pro unlocked, and carries no billing code or
 * dependency, now or ever. Its entitlement is never owned and never asked.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object StoreModule {
    @Provides
    @Singleton
    fun storeEntitlement(): StoreEntitlement = NoStore
}

private object NoStore : StoreEntitlement {
    override val verified: StateFlow<Boolean?> = MutableStateFlow(false)

    override suspend fun purchase(): PurchaseOutcome = PurchaseOutcome.Failed

    override suspend fun restore() = Unit
}
