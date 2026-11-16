// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A [ProEntitlement] for tests. It starts unlocked, so a test that is not about the free tier sees no gate. */
class FakeProEntitlement(
    initial: Boolean = true,
) : ProEntitlement {
    private val state = MutableStateFlow(initial)

    override val pro: StateFlow<Boolean> = state

    fun set(value: Boolean) {
        state.value = value
    }
}
