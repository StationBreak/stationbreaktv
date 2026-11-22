// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A [ProEntitlement] for tests. It starts unlocked, so a test that is not about the free tier sees no gate. */
class FakeProEntitlement(
    initial: Boolean = true,
) : ProEntitlement {
    private val state = MutableStateFlow(initial)

    private val gone = MutableStateFlow(false)

    override val pro: StateFlow<Boolean> = state

    override val revoked: StateFlow<Boolean> = gone

    /** A verified refund: Pro is off and [revoked] is on. A purchase (`set(true)`) clears it. */
    fun refund() {
        gone.value = true
        state.value = false
    }

    fun set(value: Boolean) {
        if (value) gone.value = false
        state.value = value
    }
}
