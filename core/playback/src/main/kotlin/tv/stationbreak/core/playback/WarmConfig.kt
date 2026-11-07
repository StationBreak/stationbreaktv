// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

/**
 * The spec 24 tunables for keeping the next channel's decoder warm: values only, with safe defaults; nothing here is
 * hard-coded where it is used. A debug build's device-side switches over them are [WarmSwitches].
 * [warmNextDecoder] is off by default: a device run measured the standby's memory cost as too high on a 1.7 GB
 * streaming stick (the standby's decoder and buffers trip the system's running-low trim), so it stays off until the
 * standby is cheaper.
 * [warmTrimLevel] is the lowest running memory-trim level that pauses warming for the visit (5 to 15; see
 * [WarmStandby.trim]).
 * [warmDeviceDenylist] lists the device models (`Build.MODEL`) that never warm, whatever Media3's own list says: the
 * Insignia (AFTJMST12), which cannot run a decoder without a surface.
 */
data class WarmConfig(
    val warmNextDecoder: Boolean = false,
    val warmNextAudio: Boolean = true,
    val warmHoldBufferMs: Long = 2_000,
    val warmReadyTimeoutMs: Long = 15_000,
    val warmDecoderGraceMs: Long = 1_500,
    val warmSeekToleranceMs: Long = 250,
    val warmMaxFailures: Int = 3,
    val warmDeviceDenylist: Set<String> = setOf("AFTJMST12"),
    // ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW: from this running level up, a trim pauses warming for the visit.
    val warmTrimLevel: Int = 10,
) {
    init {
        require(warmHoldBufferMs > 0) { "warmHoldBufferMs must be positive" }
        require(warmHoldBufferMs <= MAX_HOLD_BUFFER_MS) { "warmHoldBufferMs must be at most $MAX_HOLD_BUFFER_MS" }
        require(warmReadyTimeoutMs > 0) { "warmReadyTimeoutMs must be positive" }
        require(warmDecoderGraceMs > 0) { "warmDecoderGraceMs must be positive" }
        require(warmSeekToleranceMs > 0) { "warmSeekToleranceMs must be positive" }
        require(warmMaxFailures >= 1) { "warmMaxFailures must be at least 1" }
        require(warmTrimLevel in MIN_TRIM_LEVEL..MAX_TRIM_LEVEL) {
            "warmTrimLevel must be in $MIN_TRIM_LEVEL..$MAX_TRIM_LEVEL"
        }
    }

    private companion object {
        const val MAX_HOLD_BUFFER_MS = 10_000L

        // ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE and TRIM_MEMORY_RUNNING_CRITICAL: the running levels' ends.
        const val MIN_TRIM_LEVEL = 5
        const val MAX_TRIM_LEVEL = 15
    }
}
