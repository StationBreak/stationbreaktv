// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/** The seam the selection's resume completes (spec 26 Part 4, §2). */
class SelectionReadyTest {
    @Test
    fun theCompleteInstance_neverWaits() =
        runBlocking {
            withTimeout(1_000L) { SelectionReady.Complete.await() }
        }
}
