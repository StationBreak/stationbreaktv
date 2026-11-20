// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.stan

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which moment a host draws ([hostMoment]): a host frozen on another host's recorded moment draws exactly that moment
 * whatever its own clock says, so nothing moves; an animated host draws its clock's; reduced motion is the still frame.
 */
class StanHostMomentTest {
    private val recorded = StanHostMoment(seconds = 1.25, talking = true)

    @Test
    fun aFrozenHostDrawsTheRecordedMomentWhateverItsClockSays() {
        for (clockNanos in listOf(0L, 400_000_000L, 9_000_000_000L)) {
            assertEquals(recorded, hostMoment(recorded, reduced = false, clockNanos = clockNanos, talking = false))
        }
    }

    @Test
    fun anAnimatedHostDrawsItsClocksMoment() {
        val moment = hostMoment(null, reduced = false, clockNanos = 2_500_000_000L, talking = true)

        assertEquals(StanHostMoment(seconds = 2.5, talking = true), moment)
    }

    @Test
    fun whatAnAnimatedHostRecordsAFrozenOneDrawsAgain() {
        // The cheer records the moment it drew; the still, frozen on it, draws the same one later.
        val drawn = hostMoment(null, reduced = false, clockNanos = 1_350_000_000L, talking = false)

        assertEquals(drawn, hostMoment(drawn, reduced = false, clockNanos = 5_000_000_000L, talking = true))
    }

    @Test
    fun reducedMotionIsTheStillFrameFrozenOrNotAndNeverTalks() {
        val still = StanHostMoment(STILL_FRAME_SECONDS, talking = false)

        assertEquals(still, hostMoment(recorded, reduced = true, clockNanos = 0L, talking = true))
        assertEquals(still, hostMoment(null, reduced = true, clockNanos = 7_000_000_000L, talking = true))
    }

    @Test
    fun aRecorderStartsAtTheStartOfTheClock() {
        assertEquals(StanHostMoment(0.0, talking = false), StanHostRecorder().last)
    }
}
