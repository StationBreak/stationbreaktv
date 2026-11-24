// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tv.stationbreak.app.BuildConfig
import tv.stationbreak.core.domain.store.ProOffer
import tv.stationbreak.core.domain.store.PurchaseOutcome
import tv.stationbreak.core.domain.store.RestoreOutcome
import tv.stationbreak.core.domain.store.StoreEntitlement
import javax.inject.Singleton

/**
 * Amazon Appstore in-app purchasing (#99, #36, ADR-0032). Only this source set (the `amazon` flavor) names the Appstore
 * SDK, in [PurchasingServiceSdk] alone, so no other flavor and no test of another flavor sees it.
 *
 * `BuildConfig.PRO_ON_SALE` (ProSalePolicy in build-logic, the owner's switch) decides what is bound. Off, the store is
 * [ClosedStore], the stand-in the store release shipped before the SDK: nothing owned, `available` false, a purchase
 * or a restore fails, no price, and no SDK call is made at all (no listener is registered and nothing is asked), so a
 * missing or wrong key cannot matter. On, it is [AmazonStore], whose contract is written on that class. It asks the
 * store at start and at each resume through the process lifecycle ([attach]).
 */
@Module
@InstallIn(SingletonComponent::class)
internal object StoreModule {
    @Provides
    @Singleton
    fun storeEntitlement(
        @ApplicationContext context: Context,
        @AppScope scope: CoroutineScope,
    ): StoreEntitlement =
        storeFor(BuildConfig.PRO_ON_SALE) {
            AmazonStore(PurchasingServiceSdk(context), scope).also(::attach)
        }

    /** The Amazon release sells Pro: its Settings row opens the purchase screen (once [ClosedStore] is not bound). */
    @Provides
    fun proOffer(): ProOffer = ProOffer.Store
}

/** The store for the switch: [open] is not called unless Pro is on sale, so with it off nothing touches the SDK. */
internal fun storeFor(
    onSale: Boolean,
    open: () -> StoreEntitlement,
): StoreEntitlement = if (onSale) open() else ClosedStore

/**
 * Starts [store] now and on each foreground (the process lifecycle's start: the first activity, and each return from
 * Home). The observer is added on the main thread, which the lifecycle requires; it is told at once if the app is
 * already in the foreground.
 */
private fun attach(store: AmazonStore) {
    Handler(Looper.getMainLooper()).post {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = store.onForeground()
            },
        )
    }
}

/** What the store release ships while Pro is not on sale: nothing owned and nothing to buy. */
internal object ClosedStore : StoreEntitlement {
    override val verified: StateFlow<Boolean?> = MutableStateFlow(false)

    override suspend fun purchase(): PurchaseOutcome = PurchaseOutcome.Failed

    // No store behind it, so nothing Pro-facing shows.
    override val available: StateFlow<Boolean> = MutableStateFlow(false)

    override val price: StateFlow<String?> = MutableStateFlow(null)

    override suspend fun restore(): RestoreOutcome = RestoreOutcome.Failed
}
