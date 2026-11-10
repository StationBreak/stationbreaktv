// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Optional, and numbers only: a caller of [LibraryRepository.ruleSnapshots] that puts one in the call's coroutine
 * context is told how long the walk's transaction held the library's writer connection (#684: the hold the one walk
 * makes, which the timing line of a selection change reports as `walkTxMs`). An implementation that reads in a
 * transaction adds that wall time to [txNanos]; one that has no such transaction leaves it alone. A caller reads it
 * after the call. One caller at a time: the field is not for sharing between calls in flight.
 */
class WalkClock : AbstractCoroutineContextElement(Key) {
    /** Nanoseconds of transaction wall time reported so far; -1 until an implementation reports one. */
    @Volatile
    var txNanos: Long = NOT_MEASURED
        private set

    /** An implementation's report: [nanos] more of transaction time. */
    fun addTx(nanos: Long) {
        txNanos = if (txNanos < 0) nanos else txNanos + nanos
    }

    companion object Key : CoroutineContext.Key<WalkClock> {
        /** [txNanos] before anything was reported. */
        const val NOT_MEASURED = -1L
    }
}
