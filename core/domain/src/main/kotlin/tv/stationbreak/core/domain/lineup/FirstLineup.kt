// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Whether the active account's first lineup is still to come. After the first sync the first lineup is planned from
 * it, which takes a while on a TV, and an empty channel list means both "still building" and "really nothing": setup's
 * first-sync screen and live TV tell them apart by this. It says only what the lineup side knows; whether a channel is
 * on the air is the schedule's to say ([firstOnAir]). No sync or engine type appears here (ADR-0004); the app binds it.
 */
fun interface FirstLineup {
    /**
     * The active account's [FirstLineupState] now, then at each change: [FirstLineupState.Building] while nobody is
     * signed in.
     */
    fun firstLineup(): Flow<FirstLineupState>
}

/** Where the active account's first lineup stands. */
enum class FirstLineupState {
    /**
     * There is no lineup yet, or the planner still waits for its first choice (the collection pass it waits for has
     * not come, and it has not given up on it).
     */
    Building,

    /**
     * The first choice is made: whatever is on the air now is what this lineup has. Once settled an account's lineup
     * stays settled; a later sync never makes it [Building] again.
     */
    Settled,
}

/**
 * How long anything waits for the first lineup to settle before going on without it (about half a minute on a TV is
 * usual): setup moves on to live TV, and live TV stops standing by and says there are no channels. Neither can hang.
 */
val FIRST_LINEUP_WAIT: Duration = 3.minutes
