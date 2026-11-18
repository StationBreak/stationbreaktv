// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdmiOutputTest {
    @Test
    fun anUnpluggedSink_isGone() {
        assertFalse(sinkPresent(0))
    }

    @Test
    fun aPluggedSink_isThere() {
        assertTrue(sinkPresent(1))
    }

    @Test
    fun anyOtherReport_isNotMistakenForAGoneSink() {
        assertTrue(sinkPresent(2))
        assertTrue(sinkPresent(-1))
    }
}
