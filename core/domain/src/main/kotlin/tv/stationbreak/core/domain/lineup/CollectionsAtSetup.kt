// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ItemId

/** The "Use my collections" switch at library selection (#94): set before the choice is stored, never elsewhere. */
interface CollectionsAtSetup {
    /** True while [account] has a finalised lineup (a re-onboarding): a change to the flag then waits for tomorrow. */
    fun lineupFinalised(account: LibraryAccount): Flow<Boolean>

    /**
     * Sets [account]'s flag to [on], so the first lineup (for the choice of [libraries]) is planned with it.
     * [SetupOutcome.Applied]: saved (from the start for a new or unfinalised lineup, at the next day boundary for a
     * finalised one). [SetupOutcome.Unwritable]: the lineup may not be written, so nothing can be saved and a retry
     * will not help. [SetupOutcome.Failed]: the library could not be read or the save failed; a retry may work.
     */
    suspend fun chooseAtSetup(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        on: Boolean,
    ): SetupOutcome
}

/** What [CollectionsAtSetup.chooseAtSetup] did. */
enum class SetupOutcome { Applied, Unwritable, Failed }
