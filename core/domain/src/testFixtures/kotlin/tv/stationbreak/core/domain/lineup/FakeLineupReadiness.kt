// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import tv.stationbreak.core.domain.LibraryAccount

/** A [LineupReadiness] a test sets by hand: [state] is every account's, and the accounts asked about are kept. */
class FakeLineupReadiness(
    initial: LineupState = LineupState.Ready,
) : LineupReadiness {
    val state = MutableStateFlow(initial)

    /** The accounts [readiness] was asked for, in order. */
    val asked: MutableList<LibraryAccount> = java.util.Collections.synchronizedList(mutableListOf())

    override fun readiness(account: LibraryAccount): Flow<LineupState> {
        asked += account
        return state
    }
}
