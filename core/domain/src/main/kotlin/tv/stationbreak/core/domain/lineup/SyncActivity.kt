// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow

/**
 * Whether a sync is writing the saved library right now, for the [LineupRefresher] (#434): while it is, the refresher
 * holds the triggers that the sync's own writes cause and checks once when the writing ends. No sync type appears here
 * (ADR-0004); the app binds it.
 *
 * The app binds it to the quiet stay-current runs only. The first sync is not part of it yet: when it is, the binding
 * is where it goes, and nothing here changes.
 */
fun interface SyncActivity {
    /**
     * True while a sync is writing, false otherwise. It must give its current value first and then every change, as a
     * `StateFlow` does.
     */
    fun writing(): Flow<Boolean>
}
