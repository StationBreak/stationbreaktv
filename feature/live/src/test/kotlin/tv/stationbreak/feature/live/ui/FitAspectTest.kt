// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FitAspectTest {
    @Test
    fun anUnknownShapeStaysUnknown() {
        assertNull(fitAspect(null))
        assertNull(fitAspect(0f))
        assertNull(fitAspect(-1f))
        assertNull(fitAspect(Float.NaN))
        assertNull(fitAspect(Float.POSITIVE_INFINITY))
    }

    @Test
    fun aShapeIsRoundedToTheThousandth() {
        assertEquals(1.778f, fitAspect(16f / 9f)!!, 0.00001f)
        assertEquals(fitAspect(1.7778f), fitAspect(1.77781f))
    }
}
