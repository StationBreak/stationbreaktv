// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether StationBreak Pro is unlocked on this TV (#793). The one place every free-tier gate reads: the domain reads
 * [pro]'s value at each check, and a screen collects it, so a purchase or a refund reaches screens already open.
 */
interface ProEntitlement {
    val pro: StateFlow<Boolean>

    /**
     * True from the moment the store verifies a refund (it said Pro was owned, now it says not) until Pro is back.
     * Never true for a build that unlocks Pro, nor for a TV that never held Pro. A refund is acted on only through it.
     */
    val revoked: StateFlow<Boolean> get() = NOT_REVOKED
}

private val NOT_REVOKED: StateFlow<Boolean> = MutableStateFlow(false)

/** What the free tier allows. Commercial policy, so it lives here and not in the engine. */
object FreeTier {
    /** The most custom channels a free lineup holds. Deleting one frees a place; a stored lineup over it keeps them. */
    const val CUSTOM_CHANNELS = 5
}
