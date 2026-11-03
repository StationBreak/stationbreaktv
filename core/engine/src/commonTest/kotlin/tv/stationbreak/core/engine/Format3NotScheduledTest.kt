// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Until the scheduler reads the curated order (spec 23, Task 7), a curated channel is read and written but never
 * scheduled: the one place that would otherwise ignore it refuses it, so nothing quietly airs wrongly. The refusal is a
 * `require`, so a day build can skip the channel instead of failing.
 */
class Format3NotScheduledTest {
    @Test
    fun aCuratedChannelHasNoSchedulerSettingsYet() {
        val curated = rules(listOf(Source.Library(LIB)), formatVersion = 3, ordering = Ordering(OrderingMode.Curated))
        assertFailsWith<IllegalArgumentException> { curated.toSchedulerSettings() }
    }
}
