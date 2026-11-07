// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import tv.stationbreak.core.playback.WarmConfig
import tv.stationbreak.core.playback.WarmSwitches
import tv.stationbreak.core.playback.warmOn

/** Spec 24: the one place that decides the warm tunables and whether the device-side switches are read. */
class WarmSetupTest {
    @Test
    fun releaseReadsNoSwitches() {
        assertSame(WarmSwitches.None, warmSetupFor(switches = false).switches)
    }

    @Test
    fun aBuildThatAsksForSwitchesReadsTheLogTagSwitches() {
        assertNotSame(WarmSwitches.None, warmSetupFor(switches = true).switches)
    }

    @Test
    fun theTunablesAreTheDefaultsForEveryBuild() {
        for (switches in listOf(false, true)) {
            assertEquals(WarmConfig(), warmSetupFor(switches).config)
        }
    }

    @Test
    fun warmingIsOffByDefaultInEveryBuild() {
        for (switches in listOf(false, true)) {
            val setup = warmSetupFor(switches)
            assertFalse(setup.config.warmOn(setup.switches))
        }
    }
}
