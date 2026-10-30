// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Whether a sync is writing the saved library right now, for the [LineupRefresher] (#434): while it is, the refresher
 * holds the triggers that the sync's own writes cause and checks once when the writing ends. No sync type appears here
 * (ADR-0004); the app binds it.
 *
 * The app binds it to the first sync and the quiet stay-current runs together ([eitherWriting]): one signal for every
 * kind of sync, so the refresher's one rule covers them all.
 */
fun interface SyncActivity {
    /**
     * True while a sync is writing, false otherwise. It must give its current value first and then every change, as a
     * `StateFlow` does.
     */
    fun writing(): Flow<Boolean>
}

/**
 * A [SyncActivity] that is writing while [first] or [second] is: two sync runners, one signal. A run that begins while
 * the other still writes does not split the writing in two, so the refresher checks once when both have stopped.
 */
fun eitherWriting(
    first: Flow<Boolean>,
    second: Flow<Boolean>,
): SyncActivity = SyncActivity { combine(first, second) { a, b -> a || b } }
