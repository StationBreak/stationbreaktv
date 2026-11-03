// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.OrderingMode
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Until Task 7 (spec 23) builds the curated walk, a curated cycle is refused rather than guessed. */
class CuratedNotScheduledTest {
    @Test
    fun theCuratedCycleIsRefusedUntilTheWalkExists() {
        val settings = testSettings(orderingMode = OrderingMode.Curated)
        assertFailsWith<IllegalStateException> { Runs.cycle(settings, emptyList(), 0uL, noGroups()) }
    }
}
