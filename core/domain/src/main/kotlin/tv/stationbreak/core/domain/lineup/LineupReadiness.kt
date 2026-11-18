// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import tv.stationbreak.core.domain.LibraryAccount

/**
 * Whether an account's lineup has a channel on the air yet. The first lineup is planned after the first sync ends,
 * which takes a while on a TV, and an empty channel list means both "still building" and "really nothing": this tells
 * them apart, for setup's first-sync screen and for live TV. No sync or engine type appears here (ADR-0004); the app
 * binds it.
 */
fun interface LineupReadiness {
    /** [account]'s [LineupState] now, then at each change. */
    fun readiness(account: LibraryAccount): Flow<LineupState>
}

/** Where an account's lineup stands for a viewer about to watch. */
sealed interface LineupState {
    /**
     * Nothing on the air yet, and more is coming: there is no lineup yet, the refresher is planning it (or a sync is
     * writing, whose end it will plan from), or a first lineup still waits for its collections.
     */
    data object Building : LineupState

    /** At least one channel has something on the air now: live TV has a channel to tune. */
    data object Ready : LineupState

    /** The lineup is planned with everything it waits for, and still no channel is on the air. */
    data object Empty : LineupState
}
