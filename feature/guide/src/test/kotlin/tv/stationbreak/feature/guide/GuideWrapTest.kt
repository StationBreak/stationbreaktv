// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9

class GuideWrapTest : GuideRig() {
    @Test
    fun downOnTheLastRowWrapsToTheFirstAndUpOnTheFirstToTheLast() =
        guideTest { vm ->
            vm.open(CH9)
            runCurrent()
            vm.move(GuideDirection.Down)
            assertEquals(CH5, vm.focused().channel)
            assertEquals(0, vm.ready().firstVisibleRow)
            vm.move(GuideDirection.Up)
            assertEquals(CH9, vm.focused().channel)
        }

    @Test
    fun backAfterAWrapReturnsToThePlayingChannel() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            vm.move(GuideDirection.Up)
            vm.move(GuideDirection.Up)
            assertEquals(CH9, vm.focused().channel)
            assertTrue(vm.backToPlaying())
            assertEquals(CH7, vm.focused().channel)
        }

    @Test
    fun aHeldKeyPausesAtTheEndBeforeItWraps() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            vm.move(GuideDirection.Down)
            assertEquals(CH9, vm.focused().channel)
            // The repeats that reach the end are held there for the wrap hold, counted from the first of them.
            vm.move(GuideDirection.Down, repeat = true, atMs = 1_000)
            assertEquals(CH9, vm.focused().channel)
            vm.move(GuideDirection.Down, repeat = true, atMs = 1_300)
            assertEquals(CH9, vm.focused().channel)
            vm.move(GuideDirection.Down, repeat = true, atMs = 1_400)
            assertEquals(CH5, vm.focused().channel)
            // On from the first row the hold scrolls as it did, unpaced.
            vm.move(GuideDirection.Down, repeat = true, atMs = 1_430)
            assertEquals(CH7, vm.focused().channel)
        }

    @Test
    fun aFreshPressWrapsAtOnceEvenAfterAHeldKeyWaitedAtTheEnd() =
        guideTest { vm ->
            vm.open(CH9)
            runCurrent()
            vm.move(GuideDirection.Down, repeat = true, atMs = 1_000)
            assertEquals(CH9, vm.focused().channel)
            vm.move(GuideDirection.Down, repeat = false, atMs = 1_050)
            assertEquals(CH5, vm.focused().channel)
        }
}
