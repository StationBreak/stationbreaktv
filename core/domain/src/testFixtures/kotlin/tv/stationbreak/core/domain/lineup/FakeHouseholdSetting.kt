// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import tv.stationbreak.core.domain.LibraryAccount

/** A [HouseholdSetting] for tests: the flag is set by the test. */
class FakeHouseholdSetting(
    initial: Boolean = true,
) : HouseholdSetting {
    val flag = MutableStateFlow(initial)

    override fun useMyCollections(account: LibraryAccount): Flow<Boolean> = flag
}
