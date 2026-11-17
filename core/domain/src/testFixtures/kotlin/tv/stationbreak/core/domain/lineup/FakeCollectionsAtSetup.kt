// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ItemId

/** A [CollectionsAtSetup] for tests: the finalised state and the answer are set by the test; calls are recorded. */
class FakeCollectionsAtSetup(
    finalised: Boolean = false,
) : CollectionsAtSetup {
    val finalised = MutableStateFlow(finalised)

    /** What a setup call answers. */
    var answer: SetupOutcome = SetupOutcome.Applied

    /** The flag values [chooseAtSetup] was asked for, in order. */
    val chosen = mutableListOf<Boolean>()

    override fun lineupFinalised(account: LibraryAccount): Flow<Boolean> = finalised

    override suspend fun chooseAtSetup(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        on: Boolean,
    ): SetupOutcome {
        chosen += on
        return answer
    }
}
