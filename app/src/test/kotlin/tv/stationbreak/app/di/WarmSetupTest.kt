// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import tv.stationbreak.core.playback.WarmConfig
import tv.stationbreak.core.playback.WarmSwitches

/** Spec 24: the one place that decides the warm tunables and whether the device-side switches are read. */
class WarmSetupTest {
    @Test
    fun releaseReadsNoSwitches() {
        assertSame(WarmSwitches.None, warmSetupFor(debuggable = false, benchmark = false).switches)
    }

    @Test
    fun debugAndBenchmarkReadTheLogTagSwitches() {
        assertNotSame(WarmSwitches.None, warmSetupFor(debuggable = true, benchmark = false).switches)
        assertNotSame(WarmSwitches.None, warmSetupFor(debuggable = false, benchmark = true).switches)
    }

    @Test
    fun theTunablesAreTheDefaultsForEveryBuild() {
        for (debuggable in listOf(false, true)) {
            for (benchmark in listOf(false, true)) {
                assertEquals(WarmConfig(), warmSetupFor(debuggable, benchmark).config)
            }
        }
    }
}
