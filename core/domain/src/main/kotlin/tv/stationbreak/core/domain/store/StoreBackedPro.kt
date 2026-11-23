// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.store

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.ProEntitlement
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * "Build unlock OR store entitlement" (#99, ADR-0032). A build that unlocks Pro ([buildUnlocked]: debug, benchmark and
 * the GitHub release) is Pro and never reads the store. Any other build is Pro only while [store] has verified a
 * purchase: the last verified answer is kept in [cache], so a TV that starts offline keeps Pro, and a verified revoke
 * clears it. Until the store has answered, the cache stands unless a refund is owed; a store that answers false always
 * beats the cache.
 * [revoked] is set only by that change from owned to a verified false, so a TV that never held Pro is never refunded.
 * That refund is kept in [cache] too, before the owned answer is cleared, until a verified purchase ends it, with the
 * accounts already reset for it ([refundHandled]): a kill before a reset, or an account that signs in at a later start,
 * is still reset (#1063). A refund record that is unknown (corrupt or unreadable) is neither: it never sets [revoked]
 * or resets anything, but the cache does not grant Pro either, and the store's first verified answer settles it: true
 * ends it, false is a refund (#1067).
 * The cache is not for ever (#1065): it grants Pro for [OFFLINE_GRACE] after the store last answered true, which
 * [cache] keeps as a time. After that a TV the store has not answered for goes back to free, with nothing reset and
 * nothing deleted (the extra customs only pause, see `lockedCustoms`), and the store's next true answer restores Pro.
 * The adapter asks at every start and resume, so a TV that is online is rechecked and its time renewed.
 */
class StoreBackedPro(
    buildUnlocked: Boolean,
    private val store: StoreEntitlement,
    private val cache: ProCache,
    scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) : ProEntitlement {
    private val state = MutableStateFlow(buildUnlocked)

    private val refunded = MutableStateFlow(false)

    override val pro: StateFlow<Boolean> = state

    override val revoked: StateFlow<Boolean> = refunded

    init {
        if (!buildUnlocked) {
            scope.launch {
                val owed = cache.readRefundPending()
                if (owed == true) refunded.value = true
                // Only a refund known not owed lets the cache grant: a kill can land between writing it and the cache.
                var kept = false
                if (owed == false && cache.read() && store.verified.value == null) {
                    kept = true
                    val at = lastVerifiedTrue()
                    if (clock.now() < at + OFFLINE_GRACE) {
                        state.value = true
                        launch { lapseUnlessAnswered(at) }
                    }
                }
                launch { renewWhileVerified() }
                var unknown = owed == null
                store.verified.filterNotNull().collect {
                    // A refund is a change from owned (verified, kept from before, or perhaps hidden by an unknown
                    // record) to a verified "not owned". An unknown is one on a free TV too: no mark to reset there.
                    // A cache that granted and then lapsed still held Pro, so a refund seen after it is one too.
                    if (it) {
                        if (refunded.value || unknown) cache.writeRefundPending(false)
                        refunded.value = false
                        cache.writeVerifiedAt(clock.now())
                    } else if (state.value || kept || unknown) {
                        cache.writeRefundPending(true)
                        refunded.value = true
                    }
                    unknown = false
                    state.value = it
                    cache.write(it)
                }
            }
        }
    }

    /**
     * When the store last answered true. A cache that owns but has no readable time (it was written before the grace
     * window existed, or the value is garbled) counts as verified now, and is kept so: the window then runs from the
     * first start after the change, and a paying viewer is not locked out by an upgrade. A time in the future (the
     * clock was set back) counts as now, so the window is never longer than [OFFLINE_GRACE].
     */
    private suspend fun lastVerifiedTrue(): Instant {
        val now = clock.now()
        val kept = cache.readVerifiedAt()
        if (kept == null) cache.writeVerifiedAt(now)
        return minOf(kept ?: now, now)
    }

    /** The cache stood in for a store that has not answered: ends when the grace is over, or when the store answers. */
    private suspend fun lapseUnlessAnswered(at: Instant) {
        while (store.verified.value == null) {
            val left: Duration = at + OFFLINE_GRACE - clock.now()
            if (left <= Duration.ZERO) {
                state.value = false
                return
            }
            // Woken at least daily: a delay that stops while the TV sleeps cannot be trusted for a month.
            delay(minOf(left, RENEW_EVERY))
        }
    }

    /**
     * The store's true answer is a state, not an event: an adapter that asks at each resume and hears true again does
     * not emit it. So a long-lived process renews the time itself, daily, while that answer stands; a refund arrives as
     * a false and ends it.
     */
    private suspend fun renewWhileVerified() {
        while (true) {
            delay(RENEW_EVERY)
            if (store.verified.value == true) cache.writeVerifiedAt(clock.now())
        }
    }

    override suspend fun isRefundHandled(owner: String): Boolean = owner in cache.readRefundHandled()

    override suspend fun refundHandled(owner: String) = cache.addRefundHandled(owner)

    companion object {
        /** How long after the store's last true answer the offline cache still grants Pro (#1065, ADR-0032). */
        val OFFLINE_GRACE: Duration = 30.days

        private val RENEW_EVERY: Duration = 1.days
    }
}
