// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A [FirstSync] for tests (ADR-0004): the test sets [current], and the calls the screen made are counted. */
class InMemoryFirstSync(
    initial: LibraryStatus? = null,
) : FirstSync {
    private val state = MutableStateFlow(initial)

    /** What [status] reports now. Setting it emits to every collector. */
    var current: LibraryStatus?
        get() = state.value
        set(value) {
            state.value = value
        }

    /** How many times [ensureRunning] was called. */
    var ensureCalls = 0
        private set

    /** How many times [retry] was called. */
    var retryCalls = 0
        private set

    override fun status(): Flow<LibraryStatus?> = state

    override fun ensureRunning() {
        ensureCalls++
    }

    override fun retry() {
        retryCalls++
    }
}
