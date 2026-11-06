// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

/**
 * The spec 24 tunables for keeping the next channel's decoder warm: values only, with safe defaults; nothing here is
 * hard-coded where it is used. A debug build's device-side switches over them are [WarmSwitches].
 */
data class WarmConfig(
    val warmNextDecoder: Boolean = true,
    val warmNextAudio: Boolean = true,
    val warmHoldBufferMs: Long = 2_000,
    val warmReadyTimeoutMs: Long = 15_000,
    val warmDecoderGraceMs: Long = 1_500,
    val warmSeekToleranceMs: Long = 250,
    val warmMaxFailures: Int = 3,
) {
    init {
        require(warmHoldBufferMs > 0) { "warmHoldBufferMs must be positive" }
        require(warmHoldBufferMs <= MAX_HOLD_BUFFER_MS) { "warmHoldBufferMs must be at most $MAX_HOLD_BUFFER_MS" }
        require(warmReadyTimeoutMs > 0) { "warmReadyTimeoutMs must be positive" }
        require(warmDecoderGraceMs > 0) { "warmDecoderGraceMs must be positive" }
        require(warmSeekToleranceMs > 0) { "warmSeekToleranceMs must be positive" }
        require(warmMaxFailures >= 1) { "warmMaxFailures must be at least 1" }
    }

    private companion object {
        const val MAX_HOLD_BUFFER_MS = 10_000L
    }
}
