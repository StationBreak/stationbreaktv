// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.model.ItemId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The fixture the emulator's heap test (#684) uses: two unticks through the real turn, the second on repaired days. */
internal class RealRepairRunTest {
    private val libs =
        listOf(
            SyntheticLib(ItemId("d1".repeat(16)), '1', 300, 11L, null, false, null, false),
            SyntheticLib(ItemId("d2".repeat(16)), '2', 200, 12L, null, false, null, false),
            SyntheticLib(ItemId("d3".repeat(16)), '3', 100, 13L, null, false, null, false),
        )

    @Test
    fun theSecondUntick_walksTheNewTheCurrentAndTheRemovedSelections() =
        runBlocking {
            val run = RealRepairRun(seededLibrary(libs), ACCOUNT)
            run.prepare(libs.mapTo(LinkedHashSet()) { it.id })

            assertTrue(run.untick(setOf(libs[2].id)))
            assertTrue(run.untick(setOf(libs[1].id)))

            assertTrue(run.channelsOn > 0, "channels were switched on")
            assertTrue(run.repairs() > 0, "days were repaired")
            assertEquals(2, run.walkSizes.first { it >= 2 }, "the first untick walks the new and the removed")
            assertEquals(3, run.walkSizes.last(), "the second walks the new, the current and the removed")
        }
}
