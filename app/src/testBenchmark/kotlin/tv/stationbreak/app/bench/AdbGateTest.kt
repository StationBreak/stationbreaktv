// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbGateTest {
    @Test
    fun runsOnlyWhileDebuggingIsOn() {
        assertTrue(AdbGate.allows(1))
    }

    @Test
    fun refusesWhenDebuggingIsOffOrTheSettingIsMissingOrOdd() {
        assertFalse(AdbGate.allows(0))
        assertFalse(AdbGate.allows(-1))
        assertFalse(AdbGate.allows(2))
    }
}
