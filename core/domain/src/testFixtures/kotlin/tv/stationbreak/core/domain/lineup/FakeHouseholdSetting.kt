// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ItemId

/** A [HouseholdSetting] for tests: the flag and the finalised state are set by the test; setup calls are recorded. */
class FakeHouseholdSetting(
    initial: Boolean = true,
    finalised: Boolean = false,
) : HouseholdSetting {
    val flag = MutableStateFlow(initial)
    val finalised = MutableStateFlow(finalised)

    /** What a setup call answers. */
    var answer: SetupChoice = SetupChoice.Applied

    /** The flag values [chooseAtSetup] was asked for, in order. */
    val chosen = mutableListOf<Boolean>()

    override fun useMyCollections(account: LibraryAccount): Flow<Boolean> = flag

    override fun lineupFinalised(account: LibraryAccount): Flow<Boolean> = finalised

    override suspend fun chooseAtSetup(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        on: Boolean,
    ): SetupChoice {
        chosen += on
        return answer
    }
}
