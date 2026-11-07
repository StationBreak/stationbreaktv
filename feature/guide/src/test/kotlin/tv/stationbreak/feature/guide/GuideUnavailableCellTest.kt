// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program

/** A program that left the library shows no details of the item it names, and OK on it does not open them. */
class GuideUnavailableCellTest : GuideRig() {
    /** Channel 7 with 90 to 120 gone from the library: an `Unavailable` cell that keeps its item. */
    private fun goneAt90() {
        fake.channel(
            CH7,
            7,
            halfHours(90) + program(90, 120, 103, info = null) + halfHours(600, fromMin = 120),
        )
    }

    @Test
    fun unavailableCell_fetchesNoDetails() =
        guideTest { vm ->
            goneAt90()
            vm.open(CH7)
            runCurrent()
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)

            after(2000)

            assertEquals(
                CellKind.Unavailable,
                vm.panel.value
                    ?.cell
                    ?.kind,
            )
            assertEquals(0, server.itemDetailsCalls.size)
            assertNull(vm.panel.value?.item)
            assertFalse(checkNotNull(vm.panel.value).loading)
        }

    @Test
    fun unavailableCell_okDoesNotOpenInfo_whenNotOnNow() =
        guideTest { vm ->
            goneAt90()
            val events = mutableListOf<GuideEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
            vm.open(CH7)
            runCurrent()
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            runCurrent()

            vm.select()
            runCurrent()

            assertNull(vm.info.value)
            assertEquals(0, server.itemDetailsCalls.size)
            assertTrue(events.isEmpty())
        }
}
