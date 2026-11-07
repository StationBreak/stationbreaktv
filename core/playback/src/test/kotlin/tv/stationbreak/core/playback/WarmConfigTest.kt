// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 24: the warm tunables and their bounds. The switches over them are [WarmSwitchesTest]. */
class WarmConfigTest {
    @Test
    fun defaultsAreTheSpecValues() {
        val config = WarmConfig()
        assertFalse(config.warmNextDecoder)
        assertTrue(config.warmNextAudio)
        assertEquals(2_000L, config.warmHoldBufferMs)
        assertEquals(15_000L, config.warmReadyTimeoutMs)
        assertEquals(1_500L, config.warmDecoderGraceMs)
        assertEquals(250L, config.warmSeekToleranceMs)
        assertEquals(3, config.warmMaxFailures)
        assertEquals(10, config.warmTrimLevel)
        assertEquals(setOf("AFTJMST12"), config.warmDeviceDenylist)
    }

    @Test
    fun rejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException::class.java) { WarmConfig(warmMaxFailures = 0) }
        assertThrows(IllegalArgumentException::class.java) { WarmConfig(warmReadyTimeoutMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { WarmConfig(warmHoldBufferMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { WarmConfig(warmDecoderGraceMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { WarmConfig(warmSeekToleranceMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { WarmConfig(warmHoldBufferMs = 10_001) }
        assertEquals(10_000L, WarmConfig(warmHoldBufferMs = 10_000).warmHoldBufferMs)
        assertThrows(IllegalArgumentException::class.java) { WarmConfig(warmTrimLevel = 4) }
        assertThrows(IllegalArgumentException::class.java) { WarmConfig(warmTrimLevel = 16) }
        assertEquals(5, WarmConfig(warmTrimLevel = 5).warmTrimLevel)
        assertEquals(15, WarmConfig(warmTrimLevel = 15).warmTrimLevel)
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
