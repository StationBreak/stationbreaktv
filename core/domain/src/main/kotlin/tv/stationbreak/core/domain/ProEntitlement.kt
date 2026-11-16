// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Whether StationBreak Pro is unlocked on this TV (#793). The one place every free-tier gate reads: the domain reads
 * [pro]'s value at each check, and a screen collects it, so a purchase or a refund reaches screens already open.
 */
interface ProEntitlement {
    val pro: StateFlow<Boolean>
}

/** What the free tier allows. Commercial policy, so it lives here and not in the engine. */
object FreeTier {
    /** The most custom channels a free lineup holds. Deleting one frees a place; a stored lineup over it keeps them. */
    const val CUSTOM_CHANNELS = 5
}
