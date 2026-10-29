// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WrapHoldTest {
    private val hold = WrapHold()

    // A step that goes round the end, as a held key's repeat or a press.
    private fun wrapStep(
        atMs: Long,
        repeat: Boolean = true,
    ) = hold.allows(wraps = true, repeat = repeat, atMs = atMs)

    // A step that does not go round the end: a vertical move within the rows, or a Left or Right.
    private fun plainStep(atMs: Long) = hold.allows(wraps = false, repeat = true, atMs = atMs)

    @Test
    fun aPressWrapsAtOnce() {
        assertTrue(wrapStep(1_000, repeat = false))
    }

    @Test
    fun aHeldRepeatWaitsTheHoldFromTheFirstRepeatThatMetTheEnd() {
        assertFalse(wrapStep(1_000))
        assertFalse(wrapStep(1_399))
        assertTrue(wrapStep(1_400))
    }

    @Test
    fun theHoldIsExactlyFourHundredMilliseconds() {
        assertFalse(wrapStep(0))
        assertFalse(wrapStep(399))
        assertTrue(wrapStep(400))
    }

    @Test
    fun aWrapClearsTheWaitSoTheNextOneWaitsAgain() {
        wrapStep(0)
        assertTrue(wrapStep(400))
        assertFalse(wrapStep(430))
        assertTrue(wrapStep(830))
    }

    @Test
    fun aMoveThatIsNotAWrapResetsTheWait() {
        // Held Down waiting at the last row, then Up (or Left, or Right) steps: the wait is forgotten.
        assertFalse(wrapStep(1_000))
        assertTrue(plainStep(1_200))
        // Held Down again: a fresh 400 ms, not the 200 ms left of the old one.
        assertFalse(wrapStep(1_300))
        assertFalse(wrapStep(1_600))
        assertTrue(wrapStep(1_700))
    }

    @Test
    fun aFreshPressAfterAWaitingHoldWrapsAtOnceAndClearsTheWait() {
        assertFalse(wrapStep(1_000))
        assertTrue(wrapStep(1_100, repeat = false))
        assertFalse(wrapStep(1_150))
        assertFalse(wrapStep(1_549))
        assertTrue(wrapStep(1_550))
    }

    @Test
    fun aHeldUpWrapsFromTheFirstRowTheSameWay() {
        // The hold does not care which end: the caller says only that the step wraps.
        assertFalse(wrapStep(5_000))
        assertFalse(wrapStep(5_399))
        assertTrue(wrapStep(5_400))
    }

    @Test
    fun resetForgetsAWaitFromAnEarlierSession() {
        assertFalse(wrapStep(1_000))
        hold.reset()
        assertFalse(wrapStep(1_400))
        assertTrue(wrapStep(1_800))
    }
}
