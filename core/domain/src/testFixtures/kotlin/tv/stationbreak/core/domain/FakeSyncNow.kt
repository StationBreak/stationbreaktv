// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [SyncNow] for feature tests: [state] is the test's to set, and [start] counts each call ([starts]) and, as the real
 * one does, starts nothing while a press is [SyncNowState.Running]; otherwise it says Running until the test moves it.
 */
class FakeSyncNow(
    initial: SyncNowState = SyncNowState.Idle,
) : SyncNow {
    override val state = MutableStateFlow(initial)

    private val counted = AtomicInteger()

    /** How many times [start] was called, refused or not. */
    val starts: Int get() = counted.get()

    override fun start(): Boolean {
        counted.incrementAndGet()
        if (state.value == SyncNowState.Running) return false
        state.value = SyncNowState.Running
        return true
    }
}
