// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

/** What [WarmStandby.request] decided: build a standby, or skip with a fixed reason name. */
internal sealed interface Request {
    data object Start : Request

    data class Skip(
        val reason: String,
    ) : Request
}

/** The standby's state as seen by [WarmStandby.evaluate]. */
internal enum class Verdict { Pending, Ready, DecoderMissing, TimedOut }

/** What to do with the standby after an error: retry without audio, drop it, or stop warming altogether. */
internal enum class ErrorAction { RetryVideoOnly, Dropped, Disabled }

/** Whether a flip may swap to the standby; [reason] is `None` on a swap, else the cold-path reason. */
internal data class Decision(
    val swap: Boolean,
    val reason: WarmReason,
    val ageMs: Long,
    val readyMs: Long,
)

/**
 * Spec 24: the pure decisions of the warm standby (one hidden, muted player preparing the next channel): whether to
 * build one, when it is ready, when it has failed, the circuit breaker and whether a flip can swap to it. No Android
 * or Media3 types; the clock is injected. [deviceCannot] is asked at most once (the no-surface-decoder list).
 * Not thread-safe: call from the playback thread.
 */
@Suppress("TooManyFunctions") // One class by decision: the events, the verdict and the breaker share one state.
internal class WarmStandby(
    private val config: WarmConfig,
    private val deviceCannot: () -> Boolean,
    private val clock: () -> Long,
) {
    /** The warm key of the standby being built or held, or null. */
    var held: String? = null
        private set

    /** True when the standby keeps audio on: wanted, and not turned off by an audio failure for this player. */
    val audioOn: Boolean get() = config.audioOn() && !audioFailed

    private var audioFailed = false
    private var deviceChecked = false
    private var deviceOff = false
    private var breakerOpen = false
    private var trimPaused = false
    private var failures = 0
    private var failReason: WarmReason? = null
    private var startedAt = 0L
    private var stateReadyAt: Long? = null
    private var decoderInitMs: Long? = null
    private var readyAt: Long? = null

    @Suppress("ReturnCount") // Each early return is a reason not to start a standby.
    fun request(key: String): Request {
        if (!config.masterOn() || breakerOpen || trimPaused) return DISABLED
        if (!deviceChecked) {
            deviceChecked = true
            deviceOff = deviceCannot()
            if (deviceOff) WarmLog.line("disabled reason=deviceList failures=$failures")
        }
        if (deviceOff) return DISABLED
        if (held == key && failReason == null) return Request.Skip("alreadyWarm")
        if (held != null) WarmLog.line("dropped reason=replaced key=$held")
        held = key
        failReason = null
        startedAt = clock()
        stateReadyAt = null
        decoderInitMs = null
        readyAt = null
        return Request.Start
    }

    fun onDecoderUp(
        key: String,
        initMs: Long,
    ) {
        if (key != held) return
        decoderInitMs = initMs
        markReadyIfBoth()
    }

    fun onStateReady(key: String) {
        if (key != held || stateReadyAt != null) return
        stateReadyAt = clock()
        markReadyIfBoth()
    }

    @Suppress("ReturnCount") // Each early return is a way the error ends.
    fun onError(
        key: String,
        audio: Boolean,
    ): ErrorAction {
        if (key != held) return ErrorAction.Dropped
        if (audio && audioOn) {
            audioFailed = true
            countFailure(WarmReason.Error)
            if (breakerOpen) return ErrorAction.Disabled
            // The same standby is rebuilt without audio: its clocks start again.
            startedAt = clock()
            stateReadyAt = null
            decoderInitMs = null
            readyAt = null
            return ErrorAction.RetryVideoOnly
        }
        fail(WarmReason.Error)
        WarmLog.line("dropped reason=error key=$key")
        return if (breakerOpen) ErrorAction.Disabled else ErrorAction.Dropped
    }

    fun evaluate(): Verdict {
        if (held == null) return Verdict.Pending
        val now = clock()
        val ready = stateReadyAt
        return when {
            ready != null && decoderInitMs != null -> Verdict.Ready
            ready != null && now - ready >= config.warmDecoderGraceMs -> Verdict.DecoderMissing
            now - startedAt >= config.warmReadyTimeoutMs -> Verdict.TimedOut
            else -> Verdict.Pending
        }
    }

    /** Counts a failure of the held standby; opens the breaker at the configured number in a row. */
    fun fail(reason: WarmReason) {
        failReason = reason
        countFailure(reason)
    }

    /** The decoder never came up though playback is ready: this device cannot warm, so stop at once. */
    fun decoderMissing() {
        failReason = WarmReason.Disabled
        failures++
        openBreaker("decoderMissing")
    }

    fun decide(key: String?): Decision {
        val now = clock()
        val ready = readyAt
        val ageMs = (now - (ready ?: startedAt)).coerceAtLeast(0)
        val readyMs = if (ready != null) ready - startedAt else 0L
        val reason = coldReason(key, now)
        val swap = reason == WarmReason.None
        if (!swap) WarmLog.line("fallback reason=${reason.label} ageMs=$ageMs")
        return Decision(swap, reason, ageMs, readyMs)
    }

    private fun coldReason(
        key: String?,
        now: Long,
    ): WarmReason {
        val failed = failReason
        return when {
            key == null -> WarmReason.NoHandle
            !config.masterOn() || breakerOpen || deviceOff || trimPaused -> WarmReason.Disabled
            held == null || key != held -> WarmReason.Mismatch
            failed != null -> failed
            readyAt != null -> WarmReason.None
            now - startedAt >= config.warmReadyTimeoutMs -> WarmReason.Timeout
            else -> WarmReason.NotReady
        }
    }

    /** The standby became the front player: it is no longer held, and the failure run is over. */
    fun swapped() {
        failures = 0
        clearHeld()
    }

    fun released(reason: String) {
        if (held != null) WarmLog.line("standby released reason=$reason")
        clearHeld()
    }

    /** Memory trim: no more warming until a new instance is constructed (the next visit). */
    fun pauseForVisit() {
        trimPaused = true
    }

    private fun markReadyIfBoth() {
        val stateAt = stateReadyAt
        val init = decoderInitMs
        if (readyAt != null || stateAt == null || init == null) return
        readyAt = clock()
        WarmLog.line("ready readyMs=${clock() - startedAt} decoderInitMs=$init")
    }

    private fun countFailure(reason: WarmReason) {
        failures++
        if (failures >= config.warmMaxFailures) openBreaker(reason.label)
    }

    private fun openBreaker(reason: String) {
        breakerOpen = true
        WarmLog.line("disabled reason=$reason failures=$failures")
    }

    private fun clearHeld() {
        held = null
        failReason = null
        stateReadyAt = null
        decoderInitMs = null
        readyAt = null
    }

    private companion object {
        val DISABLED = Request.Skip("disabled")
    }
}
