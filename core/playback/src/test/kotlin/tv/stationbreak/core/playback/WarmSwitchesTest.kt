// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 24: the device-side switches over the tunables, read in debug and benchmark builds only. */
class WarmSwitchesTest {
    @Test
    fun theLogTagsTurnTheirOwnSwitchOff() {
        val masterOff = WarmSwitches.logTags { tag -> if (tag == "SBWARM") false else null }
        assertFalse(masterOff.warmAllowed())
        assertTrue(masterOff.audioAllowed())
        val audioOff = WarmSwitches.logTags { tag -> if (tag == "SBWARMAUD") false else null }
        assertTrue(audioOff.warmAllowed())
        assertFalse(audioOff.audioAllowed())
    }

    @Test
    fun anUnreadableTagLeavesTheSwitchOn() {
        val unreadable = WarmSwitches.logTags { null }
        assertTrue(unreadable.warmAllowed())
        assertTrue(unreadable.audioAllowed())
    }

    @Test
    fun noSwitchesLeaveTheTunablesToDecide() {
        assertTrue(WarmConfig(warmNextDecoder = true).warmOn(WarmSwitches.None))
        assertTrue(WarmConfig().audioOn(WarmSwitches.None))
        assertFalse(WarmConfig(warmNextDecoder = false).warmOn(WarmSwitches.None))
        assertFalse(WarmConfig(warmNextAudio = false).audioOn(WarmSwitches.None))
    }

    @Test
    fun warmingIsOffByDefault() {
        assertFalse(WarmConfig().warmOn(WarmSwitches.None))
    }

    @Test
    fun aSwitchCanOnlyTurnATunableOff() {
        val allOn = WarmSwitches.logTags { true }
        assertFalse(WarmConfig(warmNextDecoder = false).warmOn(allOn))
        assertFalse(WarmConfig(warmNextAudio = false).audioOn(allOn))
        val allOff = WarmSwitches.logTags { false }
        assertFalse(WarmConfig(warmNextDecoder = true).warmOn(allOff))
        assertFalse(WarmConfig().audioOn(allOff))
    }

    @Test
    fun theDefaultReaderDoesNotThrowOnTheJvm() {
        // The JVM log stub is unreadable or answers false; either way reading it must not throw.
        WarmSwitches.logTags().warmAllowed()
        WarmSwitches.logTags().audioAllowed()
    }

    @Test
    fun theTagsAreTheSpecNames() {
        assertTrue(WarmSwitches.MASTER_TAG == "SBWARM")
        assertTrue(WarmSwitches.AUDIO_TAG == "SBWARMAUD")
        assertTrue(WarmLog.TAG == WarmSwitches.MASTER_TAG)
    }
}
