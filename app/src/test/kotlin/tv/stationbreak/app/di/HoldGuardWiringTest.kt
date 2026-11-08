// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import org.junit.Assert.assertThrows
import org.junit.Test
import tv.stationbreak.core.domain.InMemoryLibraryRepository
import tv.stationbreak.core.domain.InMemoryLineupStore
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.repair.TodayRepairer
import tv.stationbreak.data.library.RoomLibraryRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Spec 26, Task 4.5 fix round 1 (D2): the library cache's hold-expiry clock guard is checked, where the app builds the
 * repairer, to be no longer than the pause hold the repairer is configured with.
 */
class HoldGuardWiringTest {
    private fun repairer(): TodayRepairer {
        val library = InMemoryLibraryRepository()
        val service = LineupService(InMemoryLineupStore(), library, Clock.System, { 0 }, {})
        val source = BuiltInScheduleSource(service, library) { null }
        return TodayRepairer(service, source, library) { null }
    }

    @Test
    fun theDefaultGuard_fitsTheRepairersPauseHold() {
        LineupModule.checkHoldGuard(RoomLibraryRepository.DEFAULT_HOLD_CLOCK_GUARD, repairer().pauseHold)
    }

    @Test
    fun aGuardLongerThanThePauseHold_isRefused() {
        val pause = repairer().pauseHold

        assertThrows(IllegalArgumentException::class.java) { LineupModule.checkHoldGuard(pause + 1.minutes, pause) }
    }
}
