// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ItemId

/** The "Use my collections" flag (spec 23): a feature that shows the switch follows it here, and setup sets it. */
interface HouseholdSetting {
    /**
     * [account]'s flag: on by default and while there is no lineup. It follows the lineup, and is the viewer's
     * setting; it starts to apply at the next day boundary.
     */
    fun useMyCollections(account: LibraryAccount): Flow<Boolean>

    /** True while [account] has a finalised lineup (a re-onboarding): a change to the flag then waits for tomorrow. */
    fun lineupFinalised(account: LibraryAccount): Flow<Boolean>

    /**
     * Sets [account]'s flag to [on] at library selection (#94), before the choice of [libraries] is stored, so the
     * first lineup is planned with it. [SetupChoice.Applied]: it is in force from the start (no lineup yet, or one
     * not finalised). [SetupChoice.Tomorrow]: the lineup is finalised, so it starts at the next day boundary.
     */
    suspend fun chooseAtSetup(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        on: Boolean,
    ): SetupChoice
}

/** What [HouseholdSetting.chooseAtSetup] did. */
enum class SetupChoice { Applied, Tomorrow, Failed }
