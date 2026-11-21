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
 * THE SEAM for Amazon Appstore in-app purchasing (#99, #36, ADR-0032): the only file the Appstore SDK may ever enter.
 * Only this source set (the `amazon` flavor) may name it, so no other flavor and no test sees the SDK.
 *
 * Today this is the locked stand-in: nothing is owned and a purchase fails, so the Amazon release stays free. The
 * Appstore SDK (`com.amazon.device:amazon-appstore-sdk`) is not linked yet: its Program Materials License is not
 * GPL-compatible and `licensee` rejects it, which is the owner's call (ADR-0032). The adapter that replaces
 * [AmazonStore] must:
 *
 * - register a `PurchasingListener` and set [StoreEntitlement.verified] to true only after the SDK reports a valid
 *   Pro receipt (`PurchaseResponse` SUCCESSFUL, or ALREADY_PURCHASED) and `notifyFulfillment` was called;
 * - set it to false when `getPurchaseUpdates` (asked at start and at each resume, and by [restore] with reset)
 *   reports the receipt cancelled, and to false when it lists no Pro receipt;
 * - leave it null, never false, while the SDK has not answered (offline, error), so the cache stands;
 * - never log a receipt, user ID, marketplace ID or token;
 * - take the one SKU from [PRO_SKU], the only place the SKU is named.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object StoreModule {
    @Provides
    @Singleton
    fun storeEntitlement(): StoreEntitlement = AmazonStore()
}

/** The Pro SKU as the Appstore console names it. The console's SKU is the owner's to create: this is its name here. */
internal const val PRO_SKU: String = "stationbreak.pro"

internal class AmazonStore : StoreEntitlement {
    override val verified: StateFlow<Boolean?> = MutableStateFlow(false)

    override suspend fun purchase(): PurchaseOutcome = PurchaseOutcome.Failed

    override suspend fun restore() = Unit
}
