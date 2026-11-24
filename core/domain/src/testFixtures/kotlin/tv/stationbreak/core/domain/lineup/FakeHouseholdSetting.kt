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

    /** The next day start the test says; the usual 06:00 UTC of the epoch's day one by default. */
    val changesAt = MutableStateFlow(DAY_MS + 6 * 3_600_000L)

    override fun useMyCollections(account: LibraryAccount): Flow<Boolean> = flag

    override fun changesStartAt(account: LibraryAccount): Flow<Long> = changesAt

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
