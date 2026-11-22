// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.ProEntitlement
import tv.stationbreak.core.domain.kids.KidsLock
import tv.stationbreak.core.domain.store.ProOffer
import tv.stationbreak.core.domain.store.PurchaseOutcome
import tv.stationbreak.core.domain.store.RestoreOutcome
import tv.stationbreak.core.domain.store.StoreEntitlement
import javax.inject.Inject

/** The two actions of the purchase screen, top to bottom. */
internal enum class ProChoice { Buy, Restore }

/** What the last Buy or Restore came to, said to the viewer in plain words. Nothing is kept past the screen. */
internal enum class ProResult {
    Bought,
    AlreadyOwned,
    Cancelled,
    Pending,

    /** Bought or restored, and the store has not yet reported Pro: said as "confirming", never as done. */
    Confirming,
    Failed,
    Restored,
    NothingToRestore,
    RestoreFailed,
}

/**
 * What the purchase screen shows: the [offer] of this build, whether Pro is [owned] (then there is no Buy), the store's
 * [price] as it words it (null while it has none), the action under the D-pad, whether a store call is [busy], what the
 * last one came to ([result]), whether the store left a purchase [held] (Buy stays off until its answer changes), and
 * whether Kids Mode is [locked] (no purchase is started then).
 */
internal data class ProState(
    val offer: ProOffer = ProOffer.Store,
    val owned: Boolean = false,
    val price: String? = null,
    val focus: ProChoice = ProChoice.Buy,
    val busy: Boolean = false,
    val result: ProResult? = null,
    val held: Boolean = false,
    val locked: Boolean = true,
) {
    /** Buy and Restore are offered: a store build, Pro not yet owned, no purchase held, Kids Mode off. */
    val actionable: Boolean get() = offer is ProOffer.Store && !owned && !held && !locked
}

/**
 * Holds the Pro purchase screen (#99): what Pro adds, the store's price, Buy and Restore purchases, and how each ended.
 * Both go through [StoreEntitlement]; Pro itself turns on from [ProEntitlement], which follows the store's verified
 * answer, so a purchase that lands while the screen is shut (or a refund) shows as it is. A store call runs on this
 * ViewModel's own scope, which is the guide back-stack entry's: tuning away clears the entry and cancels a call still
 * in flight, so a real adapter's purchase updates must reach [StoreEntitlement.verified] on their own. Nothing here
 * logs a receipt, a token or an ID.
 */
@HiltViewModel
internal class ProViewModel
    internal constructor(
        private val store: StoreEntitlement,
        pro: ProEntitlement,
        kids: KidsLock,
        offer: ProOffer,
        dispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        @Inject
        constructor(store: StoreEntitlement, pro: ProEntitlement, kids: KidsLock, offer: ProOffer) :
            this(store, pro, kids, offer, Dispatchers.Main)

        private val scope = CoroutineScope(SupervisorJob() + dispatcher)

        private data class Local(
            val focus: ProChoice = ProChoice.Buy,
            val busy: Boolean = false,
            val result: ProResult? = null,
            val held: Boolean = false,
        )

        private val local = MutableStateFlow(Local())

        // Kids Mode counts as on until the stored lock has been read.
        private val kidsOn = kids.state.map { it.enabled }.onStart { emit(true) }

        val state: StateFlow<ProState> =
            combine(local, pro.pro, store.price, kidsOn) { l, owned, price, locked ->
                ProState(offer, owned, price, l.focus, l.busy, said(l.result, owned), l.held, locked)
            }.stateIn(scope, SharingStarted.Eagerly, ProState(offer))

        init {
            // A refund wipes what was said; a purchase landing wipes a stale Pending (Bought and Restored stay).
            scope.launch {
                var was: Boolean? = null
                pro.pro.collect { owned ->
                    if (was != null && was != owned) {
                        local.update { it.copy(result = it.result.takeIf { r -> owned && r in DONE }) }
                    }
                    was = owned
                }
            }
            // The store's answer moving ends a hold: Buy may start again.
            scope.launch {
                var was: Boolean? = store.verified.value
                store.verified.collect { v ->
                    if (v != was) local.update { it.copy(held = false) }
                    was = v
                }
            }
        }

        override fun onCleared() {
            scope.cancel()
        }

        /** The screen shows again: on Buy, with nothing said yet. A store call still running keeps its state. */
        fun open() {
            local.update { if (it.busy) it else Local() }
        }

        /** Up to Buy, Down to Restore purchases. */
        fun move(up: Boolean) {
            local.update { it.copy(focus = if (up) ProChoice.Buy else ProChoice.Restore) }
        }

        /** OK: starts the focused action, once; nothing while a call runs, with Pro owned, or with Kids Mode on. */
        fun select() {
            if (!state.value.actionable) return
            var choice: ProChoice? = null
            local.update {
                if (it.busy) {
                    it
                } else {
                    choice = it.focus
                    it.copy(busy = true, result = null)
                }
            }
            val asked = choice ?: return
            scope.launch {
                val result = ask(asked)
                local.update { it.copy(busy = false, result = result, held = result == ProResult.Pending) }
            }
        }

        // A throw from the store is one failure the viewer is told of; its message could carry an ID, so it is dropped.
        @Suppress("TooGenericExceptionCaught")
        private suspend fun ask(choice: ProChoice): ProResult =
            try {
                when (choice) {
                    ProChoice.Buy -> store.purchase().result()
                    ProChoice.Restore -> store.restore().result()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (choice == ProChoice.Buy) ProResult.Failed else ProResult.RestoreFailed
            }
    }

private val DONE = setOf(ProResult.Bought, ProResult.AlreadyOwned, ProResult.Restored)

// A success is said only once Pro is owned (until then: confirming); the rest only while it is not.
internal fun said(
    result: ProResult?,
    owned: Boolean,
): ProResult? =
    when {
        result == null -> null
        result in DONE -> if (owned) result else ProResult.Confirming
        else -> result.takeUnless { owned }
    }

private fun PurchaseOutcome.result(): ProResult =
    when (this) {
        PurchaseOutcome.Purchased -> ProResult.Bought
        PurchaseOutcome.AlreadyOwned -> ProResult.AlreadyOwned
        PurchaseOutcome.Cancelled -> ProResult.Cancelled
        PurchaseOutcome.Failed -> ProResult.Failed
        PurchaseOutcome.Pending -> ProResult.Pending
    }

private fun RestoreOutcome.result(): ProResult =
    when (this) {
        RestoreOutcome.Found -> ProResult.Restored
        RestoreOutcome.None -> ProResult.NothingToRestore
        RestoreOutcome.Failed -> ProResult.RestoreFailed
    }
