// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.util.Log

/**
 * The spec 24 tunables for keeping the next channel's decoder warm. Safe defaults; nothing here is hard-coded where
 * it is used. [property] reads an Android log tag switch (injected so tests need no log service); it is consulted only
 * when [debugSwitches] is set (debug and benchmark builds), never in release.
 */
data class WarmConfig(
    val warmNextDecoder: Boolean = true,
    val warmNextAudio: Boolean = true,
    val warmHoldBufferMs: Long = 2_000,
    val warmReadyTimeoutMs: Long = 15_000,
    val warmDecoderGraceMs: Long = 1_500,
    val warmSeekToleranceMs: Long = 250,
    val warmMaxFailures: Int = 3,
    val debugSwitches: Boolean = false,
    val property: (String) -> Boolean? = ::systemLoggable,
) {
    init {
        require(warmHoldBufferMs > 0) { "warmHoldBufferMs must be positive" }
        require(warmHoldBufferMs <= MAX_HOLD_BUFFER_MS) { "warmHoldBufferMs must be at most $MAX_HOLD_BUFFER_MS" }
        require(warmReadyTimeoutMs > 0) { "warmReadyTimeoutMs must be positive" }
        require(warmDecoderGraceMs > 0) { "warmDecoderGraceMs must be positive" }
        require(warmSeekToleranceMs > 0) { "warmSeekToleranceMs must be positive" }
        require(warmMaxFailures >= 1) { "warmMaxFailures must be at least 1" }
    }

    /** True when warming is wanted: the tunable, and in debug builds the `log.tag.SBWARM` switch not set to ASSERT. */
    fun masterOn(): Boolean = warmNextDecoder && switchOn(WarmLog.TAG)

    /** True when the standby keeps audio on (muted): the tunable, and in debug `log.tag.SBWARMAUD` not ASSERT. */
    fun audioOn(): Boolean = warmNextAudio && switchOn(AUDIO_TAG)

    private fun switchOn(tag: String): Boolean = !debugSwitches || property(tag) != false

    companion object {
        /** The system property tag of the audio switch. */
        const val AUDIO_TAG = "SBWARMAUD"
        private const val MAX_HOLD_BUFFER_MS = 10_000L
    }
}

/** Reads `log.tag.<tag>`: true when WARN is loggable (not ASSERT), null where the log service is unreadable. */
internal fun systemLoggable(tag: String): Boolean? = runCatching { Log.isLoggable(tag, Log.WARN) }.getOrNull()

/** Why a flip did not take the warm path; the `warmReason=` field of the SBLIVE lines. */
enum class WarmReason(
    val label: String,
) {
    None("none"),
    Disabled("disabled"),
    NotReady("notReady"),
    Mismatch("mismatch"),
    Error("error"),
    Timeout("timeout"),
    WrongDirection("wrongDirection"),
    Transcode("transcode"),
    NoHandle("noHandle"),
}
