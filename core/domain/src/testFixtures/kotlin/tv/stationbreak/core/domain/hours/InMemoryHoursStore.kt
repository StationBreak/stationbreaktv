// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.hours

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException

/** An [HoursStore] in memory. [failWrites] makes every save throw, as a full or broken disk would. */
class InMemoryHoursStore(
    initial: HoursSchedule = HoursSchedule.EMPTY,
) : HoursStore {
    val current = MutableStateFlow(initial)
    var failWrites = false

    override val schedule: Flow<HoursSchedule> = current

    override suspend fun save(schedule: HoursSchedule) {
        if (failWrites) throw IOException("test write failure")
        current.value = schedule
    }
}
