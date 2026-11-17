// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionReducedTest {
    @Test
    fun aScaleOfZeroIsReducedMotion() = assertTrue(isMotionReduced(0f))

    @Test
    fun anyOtherScaleKeepsMotion() {
        assertFalse(isMotionReduced(1f))
        assertFalse(isMotionReduced(0.5f))
        assertFalse(isMotionReduced(10f))
    }
}
