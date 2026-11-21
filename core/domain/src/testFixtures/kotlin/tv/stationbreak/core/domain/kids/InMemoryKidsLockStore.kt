// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.kids

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException

/** A [KidsLockStore] in memory. [failWrites] makes every update throw, as a full or broken disk would. */
class InMemoryKidsLockStore(
    initial: KidsLockRecord = KidsLockRecord(),
) : KidsLockStore {
    val current = MutableStateFlow(initial)
    var failWrites = false

    override val record: Flow<KidsLockRecord> = current

    override suspend fun update(change: (KidsLockRecord) -> KidsLockRecord): KidsLockRecord {
        if (failWrites) throw IOException("test write failure")
        return change(current.value).also { current.value = it }
    }
}
