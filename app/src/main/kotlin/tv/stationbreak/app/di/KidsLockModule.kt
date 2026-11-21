// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tv.stationbreak.core.domain.kids.KidsIdleFallback
import tv.stationbreak.core.domain.kids.KidsLock
import tv.stationbreak.core.domain.kids.KidsLockStore
import tv.stationbreak.core.domain.kids.MonotonicClock
import tv.stationbreak.core.domain.kids.PinKidsLock
import tv.stationbreak.data.settings.kids.DataStoreKidsLockStore
import javax.inject.Singleton
import kotlin.time.Clock

private const val TAG = "KidsLock"

/**
 * The Kids Mode lock (#789): its file (one DataStore file of its own, one per process as DataStore requires) and the
 * one lock over it. [LineupModule] wraps the lineup and the schedule features read in it. The log lines are event
 * names and exception class names only, never a PIN. The lockout is also timed on `SystemClock.elapsedRealtime()`,
 * which setting the wall clock does not move.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object KidsLockModule {
    @Provides
    @Singleton
    fun kidsLockStore(
        @ApplicationContext context: Context,
    ): KidsLockStore = DataStoreKidsLockStore.create(context) { Log.w(TAG, it) }

    @Provides
    @Singleton
    fun monotonicClock(): MonotonicClock = MonotonicClock(SystemClock::elapsedRealtime)

    @Provides
    @Singleton
    fun kidsLock(
        store: KidsLockStore,
        clock: Clock,
        monotonic: MonotonicClock,
    ): KidsLock = PinKidsLock(store, clock, monotonic, report = { Log.i(TAG, it.name) })

    /** The idle fallback (free): the one that [MainActivity] feeds key presses and the app runs for the process. */
    @Provides
    @Singleton
    fun kidsIdleFallback(
        lock: KidsLock,
        monotonic: MonotonicClock,
    ): KidsIdleFallback = KidsIdleFallback(lock, monotonic)
}
