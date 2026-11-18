// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A [FirstLineup] a test sets by hand; settled unless it says otherwise. [asked] counts the times it was looked at. */
class FakeFirstLineup(
    initial: FirstLineupState = FirstLineupState.Settled,
) : FirstLineup {
    val state = MutableStateFlow(initial)

    @Volatile
    var asked = 0
        private set

    override fun firstLineup(): Flow<FirstLineupState> {
        asked++
        return state
    }
}
