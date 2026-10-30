// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Back and Ahead settle (spec 20): the flip's rule (#430, R55) on a clock the test moves. */
class StepSettleTest {
    private var now = 0L
    private val timings = LiveTimings()
    private val settle = StepSettle(timings) { now }

    private fun at(ms: Long): StepSettle {
        now = ms
        return settle
    }

    @Test
    fun whileTheKeyIsDownOnlyTheReleaseFallbackSettles() {
        assertTrue(at(0).key(repeat = false, atMs = 0))
        settle.aim(-10_000, live = false)
        assertEquals(1_000L, settle.dueAtMs)
        assertFalse(at(999).isDue())
        assertTrue(at(1_000).isDue())
        assertEquals(StepSettle.Target(-10_000, false), settle.take())
        assertNull(settle.dueAtMs)
        assertNull(settle.target)
    }

    @Test
    fun aTapSettlesFlipSettleAfterItsPress() {
        at(0).key(repeat = false, atMs = 0)
        settle.aim(5, live = true)
        assertFalse(at(100).released())
        assertEquals(400L, settle.dueAtMs)
        assertFalse(at(399).isDue())
        assertTrue(at(400).isDue())
        assertEquals(StepSettle.Target(5, true), settle.take())
    }

    @Test
    fun aRepeatPutsTheFallbackOffEvenWhenItDoesNotStep() {
        at(0).key(repeat = false, atMs = 0)
        assertFalse(at(50).key(repeat = true, atMs = 50))
        assertEquals(1_050L, settle.dueAtMs)
    }

    @Test
    fun aReleaseLongAfterTheLastRepeatSettlesAtOnce() {
        at(0).key(repeat = false, atMs = 0)
        at(900).key(repeat = true, atMs = 900)
        settle.aim(1, live = false)
        assertTrue(at(1_400).released())
        assertEquals(1_400L, settle.dueAtMs)
    }

    @Test
    fun aStrayReleaseDoesNothing() {
        assertFalse(at(0).released())
        assertNull(settle.dueAtMs)
    }

    @Test
    fun repeatsStepAtTheFlipPace() {
        assertTrue(at(0).key(repeat = false, atMs = 0))
        assertFalse(at(50).key(repeat = true, atMs = 50))
        assertTrue(at(400).key(repeat = true, atMs = 400))
        assertFalse(at(500).key(repeat = true, atMs = 500))
        assertTrue(at(650).key(repeat = true, atMs = 650))
    }

    @Test
    fun aRepeatWithNoKeyTimeStepsOnTheClock() {
        at(1_000).key(repeat = false, atMs = null)
        assertTrue(at(1_400).key(repeat = true, atMs = null))
    }

    @Test
    fun dropGivesBackThePendingTargetAndClearsTheDeadline() {
        at(0).key(repeat = false, atMs = 0)
        settle.aim(7, live = false)
        assertEquals(StepSettle.Target(7, false), settle.drop())
        assertNull(settle.dueAtMs)
        assertNull(settle.drop())
    }
}
