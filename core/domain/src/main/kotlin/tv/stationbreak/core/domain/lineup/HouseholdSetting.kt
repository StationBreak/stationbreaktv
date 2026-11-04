// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import tv.stationbreak.core.domain.LibraryAccount

/** The "Use my collections" flag (spec 23), read: a feature that shows the switch follows it here. */
interface HouseholdSetting {
    /**
     * [account]'s flag: on by default and while there is no lineup. It follows the lineup, and is the viewer's
     * setting; it starts to apply at the next day boundary.
     */
    fun useMyCollections(account: LibraryAccount): Flow<Boolean>
}
