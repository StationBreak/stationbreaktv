// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tv.stationbreak.core.domain.ProEntitlement
import tv.stationbreak.core.domain.hours.HoursOfOperation
import tv.stationbreak.core.domain.hours.HoursStore
import tv.stationbreak.core.domain.kids.KidsLock
import tv.stationbreak.data.settings.hours.DataStoreHoursStore
import javax.inject.Singleton

/**
 * Hours of operation (#789, ADR-0033): this TV's schedule file (one DataStore file of its own, one per process as
 * DataStore requires) and the feature over it, behind the Pro gate and the Kids lock's PIN. The Settings sheet's
 * hours screen reads and edits it; nothing enforces it yet (the player and the guide are a later item).
 */
@Module
@InstallIn(SingletonComponent::class)
internal object HoursModule {
    @Provides
    @Singleton
    fun hoursStore(
        @ApplicationContext context: Context,
    ): HoursStore = DataStoreHoursStore.create(context)

    @Provides
    @Singleton
    fun hoursOfOperation(
        store: HoursStore,
        pro: ProEntitlement,
        lock: KidsLock,
    ): HoursOfOperation = HoursOfOperation(store, pro, lock)
}
