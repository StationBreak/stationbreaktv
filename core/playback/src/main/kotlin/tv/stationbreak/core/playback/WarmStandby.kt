// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import kotlin.time.Duration

/**
 * True when a play of [play] may use a standby built from [held]: the same source in every respect (address, kind,
 * subtitles, track choices) but the start position, which moves with live time between the warm and the flip, and the
 * warm handle stamped on it. A route that fell back or an item that was replaced is another source, so the handle alone
 * never decides a hand-over.
 */
internal fun sameStandbySource(
    held: PlayerSource?,
    play: PlayerSource,
): Boolean = held != null && held.normalised() == play.normalised()

private fun PlayerSource.normalised(): PlayerSource = copy(playerStart = Duration.ZERO, warmKey = null)

/** What [WarmStandby.request] decided: build a standby, or skip with a fixed reason name. */
internal sealed interface Request {
    data object Start : Request

    data class Skip(
        val reason: String,
    ) : Request
}

/** The standby's state as seen by [WarmStandby.evaluate]. */
internal enum class Verdict { Pending, Ready, DecoderMissing, TimedOut }

/**
 * What to do with the standby after an error: retry without audio, drop it, or stop warming altogether. [Ignored]
 * is an event from an older standby (another key or epoch): do nothing.
 */
internal enum class ErrorAction { RetryVideoOnly, Dropped, Disabled, Ignored }

/** Whether a flip may swap to the standby; [reason] is `None` on a swap, else the cold-path reason. */
internal data class Decision(
    val swap: Boolean,
    val reason: WarmReason,
    val ageMs: Long,
    val readyMs: Long,
)

/** The player's numbers on the `ready` and `fallback` lines (frames, buffer, heap); empty by default. */
internal class StandbyFields(
    val ready: () -> String = { "" },
    val fallback: () -> String = { "" },
)

/**
 * Spec 24: the pure decisions of the warm standby (one hidden, muted player preparing the next channel): whether to
 * build one, when it is ready, when it has failed, the circuit breaker and whether a flip can swap to it. No Android
 * or Media3 types; the clock is injected. [deviceCannot] is asked at most once: it gives the reason this device
 * cannot warm ([DeviceWarmth]), or null when it can.
 * [switches] are the device-side switches over [config] (debug builds).
 * Not thread-safe: call from the playback thread.
 *
 * Caller contract: [evaluate] has no side effects. On [Verdict.TimedOut] the caller calls `fail(WarmReason.Timeout)`;
 * on [Verdict.DecoderMissing] it calls [decoderMissing]. Both count at most one failure per standby, so repeated
 * calls (for example one per poll) are safe. Every standby build, and every audio retry, gets a new [epoch]; events
 * carry the epoch they were made for and those from an older instance are ignored. [fields] are appended to the
 * `ready` and `fallback` lines.
 */
@Suppress("TooManyFunctions") // One class by decision: the events, the verdict and the breaker share one state.
internal class WarmStandby(
    private val config: WarmConfig,
    private val switches: WarmSwitches,
    private val deviceCannot: () -> String?,
    private val clock: () -> Long,
    private val log: (String) -> Unit = WarmLog::line,
    private val fields: StandbyFields = StandbyFields(),
) {
    /** The warm key of the standby being built or held, or null. */
    var held: String? = null
        private set

    /** Identifies the current standby build; changes on each start and each audio retry. Pass it back with events. */
    var epoch: Int = 0
        private set

    /** True when the standby keeps audio on: wanted, and not turned off by an audio failure for this player. */
    val audioOn: Boolean get() = config.audioOn(switches) && !audioFailed

    /** True when no standby will be started: switched off, the device cannot, the breaker is open, or trimmed. */
    val disabled: Boolean get() = !config.warmOn(switches) || deviceOff || breakerOpen || trimPaused

    private var audioFailed = false
    private var disabledSaid = false
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

    /**
     * Whether to start a standby for [key]. A skip is logged (`skipped key reason`); `disabled` once per instance
     * (the player), as warming stays off for its life and the warm calls go on.
     */
    fun request(key: String): Request = startOrSkip(key).also { if (it is Request.Skip) saySkipped(key, it) }

    /**
     * When to call [evaluate] next, in ms from now: the decoder grace once the standby is ready to play, else the
     * ready timeout, whichever comes first (0 when one has passed); null when there is nothing to watch (none held,
     * ready, or failed). The player checks once at that time instead of polling.
     */
    fun nextCheckInMs(): Long? {
        if (held == null || failReason != null || readyAt != null) return null
        val now = clock()
        val timeoutIn = startedAt + config.warmReadyTimeoutMs - now
        val graceIn = stateReadyAt?.let { it + config.warmDecoderGraceMs - now } ?: timeoutIn
        return minOf(timeoutIn, graceIn).coerceAtLeast(0)
    }

    @Suppress("ReturnCount") // Each early return is a reason not to start a standby.
    private fun startOrSkip(key: String): Request {
        if (!config.warmOn(switches) || breakerOpen || trimPaused) return DISABLED
        if (!deviceChecked) {
            deviceChecked = true
            val reason = deviceCannot()
            deviceOff = reason != null
            if (reason != null) log("disabled reason=$reason failures=$failures")
        }
        if (deviceOff) return DISABLED
        if (held == key && failReason == null) return Request.Skip("alreadyWarm")
        // The same key held but failed is rebuilt (not a replacement); a different key replaces the held one.
        if (held != null && held != key) log("dropped reason=replaced key=$held")
        held = key
        failReason = null
        restartClocks()
        return Request.Start
    }

    fun onDecoderUp(
        key: String,
        initMs: Long,
        epoch: Int = this.epoch,
    ) {
        if (key != held || epoch != this.epoch) return
        decoderInitMs = initMs
        markReadyIfBoth()
    }

    fun onStateReady(
        key: String,
        epoch: Int = this.epoch,
    ) {
        if (key != held || epoch != this.epoch || stateReadyAt != null) return
        stateReadyAt = clock()
        markReadyIfBoth()
    }

    @Suppress("ReturnCount") // Each early return is a way the error ends.
    fun onError(
        key: String,
        audio: Boolean,
        epoch: Int = this.epoch,
    ): ErrorAction {
        if (key != held || epoch != this.epoch) return ErrorAction.Ignored
        if (audio && audioOn) {
            audioFailed = true
            countFailure(WarmReason.Error)
            if (breakerOpen) return ErrorAction.Disabled
            // The same standby is rebuilt without audio: its clocks start again.
            restartClocks()
            return ErrorAction.RetryVideoOnly
        }
        fail(WarmReason.Error)
        log("dropped reason=error key=$key")
        return if (breakerOpen) ErrorAction.Disabled else ErrorAction.Dropped
    }

    /** The standby's state now. Side-effect free: the caller acts on the verdict (see the class comment). */
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

    /**
     * Counts a failure of the held standby; opens the breaker at the configured number in a row. Counts at most one
     * failure per standby: a no-op when none is held or it has already failed.
     */
    fun fail(reason: WarmReason) {
        if (held == null || failReason != null) return
        failReason = reason
        countFailure(reason)
    }

    /** The decoder never came up though playback is ready: this device cannot warm, so stop at once. */
    fun decoderMissing() {
        if (held == null || failReason != null) return
        failReason = WarmReason.Disabled
        failures++
        openBreaker("decoderMissing")
    }

    /**
     * Whether a play with handle [key] may swap to the standby. [sameSource] is false when the play's source is not
     * the one the standby was built from ([sameStandbySource]): then it is a [WarmReason.Mismatch], as for another
     * key, and not a failure of the standby.
     */
    fun decide(
        key: String?,
        sameSource: Boolean = true,
    ): Decision {
        val now = clock()
        val ready = readyAt
        val ageMs = (now - (ready ?: startedAt)).coerceAtLeast(0)
        val readyMs = if (ready != null) ready - startedAt else 0L
        val reason = coldReason(key, sameSource, now)
        val swap = reason == WarmReason.None
        // A cold flip with no standby at all is no fallback: there was nothing to fall back from.
        val nothingHeld = held == null && reason == WarmReason.Mismatch
        if (!swap && !nothingHeld) log(withFields("fallback reason=${reason.label} ageMs=$ageMs", fields.fallback))
        return Decision(swap, reason, ageMs, readyMs)
    }

    private fun coldReason(
        key: String?,
        sameSource: Boolean,
        now: Long,
    ): WarmReason {
        val failed = failReason
        return when {
            key == null -> WarmReason.NoHandle
            !config.warmOn(switches) || breakerOpen || deviceOff || trimPaused -> WarmReason.Disabled
            held == null || key != held || !sameSource -> WarmReason.Mismatch
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

    /** The held standby is let go of without a failure (a cold flip, or the caller dropped it); the player stays. */
    fun dropped(reason: String) {
        val key = held ?: return
        log("dropped reason=$reason key=$key")
        clearHeld()
    }

    fun released(reason: String) {
        if (held != null) log("standby released reason=$reason")
        clearHeld()
    }

    /** Memory trim: no more warming until a new instance is constructed (the next visit). */
    fun pauseForVisit() {
        trimPaused = true
    }

    private fun saySkipped(
        key: String,
        skip: Request.Skip,
    ) {
        if (skip == DISABLED) {
            if (disabledSaid) return
            disabledSaid = true
        }
        log("skipped key=$key reason=${skip.reason}")
    }

    private fun markReadyIfBoth() {
        val stateAt = stateReadyAt
        val init = decoderInitMs
        if (readyAt != null || stateAt == null || init == null) return
        val now = clock()
        readyAt = now
        log(withFields("ready readyMs=${now - startedAt} decoderInitMs=$init", fields.ready))
    }

    private fun withFields(
        line: String,
        extra: () -> String,
    ): String = extra().let { if (it.isEmpty()) line else "$line $it" }

    private fun countFailure(reason: WarmReason) {
        failures++
        if (failures >= config.warmMaxFailures) openBreaker(reason.label)
    }

    private fun openBreaker(reason: String) {
        breakerOpen = true
        log("disabled reason=$reason failures=$failures")
    }

    private fun restartClocks() {
        epoch++
        startedAt = clock()
        stateReadyAt = null
        decoderInitMs = null
        readyAt = null
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
