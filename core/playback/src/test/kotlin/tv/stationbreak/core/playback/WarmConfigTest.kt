// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 24: the warm tunables, their bounds, and the debug-only property switches. */
class WarmConfigTest {
    private val masterOff: (String) -> Boolean? = { tag -> if (tag == "SBWARM") false else null }

    @Test
    fun defaultsAreTheSpecValues() {
        val config = WarmConfig()
        assertTrue(config.warmNextDecoder)
        assertTrue(config.warmNextAudio)
        assertEquals(2_000L, config.warmHoldBufferMs)
        assertEquals(15_000L, config.warmReadyTimeoutMs)
        assertEquals(1_500L, config.warmDecoderGraceMs)
        assertEquals(250L, config.warmSeekToleranceMs)
        assertEquals(3, config.warmMaxFailures)
        assertFalse(config.debugSwitches)
    }

    @Test
    fun rejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException::class.java) { WarmConfig(warmMaxFailures = 0) }
        assertThrows(IllegalArgumentException::class.java) { WarmConfig(warmReadyTimeoutMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { WarmConfig(warmHoldBufferMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { WarmConfig(warmDecoderGraceMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { WarmConfig(warmSeekToleranceMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { WarmConfig(warmHoldBufferMs = 10_001) }
        assertTrue(WarmConfig(warmHoldBufferMs = 10_000).masterOn())
    }

    @Test
    fun tunableOffMeansMasterAndAudioOff() {
        assertFalse(WarmConfig(warmNextDecoder = false).masterOn())
        assertFalse(WarmConfig(warmNextAudio = false).audioOn())
    }

    @Test
    fun debugSwitchesReadTheProperties() {
        val config = WarmConfig(debugSwitches = true, property = masterOff)
        assertFalse(config.masterOn())
        assertTrue(config.audioOn())
        val audioOff = WarmConfig(debugSwitches = true, property = { tag -> if (tag == "SBWARMAUD") false else null })
        assertTrue(audioOff.masterOn())
        assertFalse(audioOff.audioOn())
    }

    @Test
    fun releaseIgnoresTheProperties() {
        val config = WarmConfig(debugSwitches = false, property = { false })
        assertTrue(config.masterOn())
        assertTrue(config.audioOn())
    }

    @Test
    fun theDefaultPropertyDoesNotThrowOnTheJvm() {
        // The JVM log stub is unreadable or answers false; either way reading it must not throw.
        WarmConfig(debugSwitches = true).masterOn()
        WarmConfig(debugSwitches = true).audioOn()
    }

    @Test
    fun reasonLabelsAreTheTelemetryNames() {
        assertEquals(
            listOf(
                "none",
                "disabled",
                "notReady",
                "mismatch",
                "error",
                "timeout",
                "wrongDirection",
                "transcode",
                "noHandle",
            ),
            WarmReason.entries.map { it.label },
        )
    }
}
