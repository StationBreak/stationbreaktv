// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.upstream.DefaultAllocator
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One ExoPlayer and what is its own: its media buffer, its load counter, and its load control, which stops loading
 * at the hold while the player is a waiting standby ([holding]).
 */
@UnstableApi
internal class Slot(
    val exo: ExoPlayer,
    val allocator: DefaultAllocator,
    val loadCounter: LoadCounter,
    val holding: AtomicBoolean,
) {
    val skips = SkippedFrames(exo)
}

/** What [StandbySlots] needs of the player it serves. Called on the player's looper. */
@UnstableApi
internal interface SlotHost {
    /** The front role's analytics listener: on the front ExoPlayer only, moved to the new front at a hand-over. */
    val frontAnalytics: AnalyticsListener

    /** The front role's player listener (the picture's shape): moved with [frontAnalytics]. */
    val frontListener: Player.Listener

    /** Builds one more ExoPlayer with its own buffer, load counter and holding load control. */
    fun newSlot(): Slot

    /** Puts the app's output (a view or a bare surface) on [target]; with none, clears [target]'s. */
    fun drawOn(target: ExoPlayer)

    /** Why this device cannot warm ([DeviceWarmth]), or null when it can; asked once per player. */
    fun deviceCannot(): String?

    /** The runtime probe found no decoder behind a ready standby: kept for the process ([DeviceWarmth]). */
    fun decoderMissing()
}

/** What the hand-over logs. */
internal class Handover(
    val key: String,
    val ageMs: Long,
    val detachMs: Long,
)

/** What became of the standby at a play: its [outcome], and the [handover] when the play swapped to it. */
internal class Taken(
    val outcome: WarmOutcome,
    val handover: Handover?,
)

/**
 * Spec 24: the player's two ExoPlayers and the standby's life between them. The [front] draws on the app's surface;
 * the standby (built on the first started [warm]) prepares the next channel hidden, muted and paused, its buffer held.
 * [take] decides a play: a hand-over moves the surface to the standby and the two swap roles, and every other play
 * stays on the front. The role is structural: the front's listeners ([SlotHost.frontAnalytics],
 * [SlotHost.frontListener]) are on the front ExoPlayer only and the standby's listener on the standby only; a
 * hand-over moves both. The decisions themselves are [WarmStandby]'s. Looper only.
 *
 * The player's `play` must not be called from inside a Media3 listener callback on its own ExoPlayers: a hand-over
 * stops one of them and moves the listeners, which a callback still being delivered on that player would straddle.
 */
@UnstableApi
@Suppress("TooManyFunctions") // The standby's life (start, check, errors, drop, release, hand-over) shares one state.
internal class StandbySlots(
    front: Slot,
    private val handler: Handler,
    private val clock: () -> Long,
    config: WarmConfig,
    switches: WarmSwitches,
    private val host: SlotHost,
) {
    /** The player on screen. Swapped with the standby by a hand-over. Written on the looper. */
    @Volatile var front: Slot = front
        private set

    /** The standby player, built on the first started warm; null before and once released. Written on the looper. */
    @Volatile private var spare: Slot? = null

    /** The standby Media3 player, if built, for this module's instrumented tests. */
    val standbyExo: ExoPlayer? get() = spare?.exo

    private val state =
        WarmStandby(
            config = config,
            switches = switches,
            deviceCannot = host::deviceCannot,
            clock = clock,
            fields = StandbyFields(ready = ::readyFields, fallback = WarmLog::memoryFields),
        )

    /** The source the standby was last started with, for a video-only retry. */
    private var standbySource: PlayerSource? = null

    /** Acts on [WarmStandby.evaluate] at the standby's next deadline ([scheduleCheck]). */
    private val standbyCheck = Runnable { checkStandby() }

    /** Set by [release]: no standby is built or started after it. */
    private var released = false

    private val standbyAnalytics = StandbyAnalytics()

    init {
        front.exo.addAnalyticsListener(host.frontAnalytics)
        front.exo.addListener(host.frontListener)
    }

    /**
     * A warm of [source]: the held handle when the standby already holds that source (the start aside) and has not
     * failed, with nothing prepared again; otherwise a new handle from [issue], which replaces the held standby.
     */
    fun warmOrReuse(
        source: PlayerSource,
        issue: () -> String,
    ): String {
        if (!released) state.reuseFor(sameStandbySource(standbySource, source))?.let { return it }
        return issue().also { warm(it, source) }
    }

    /** Starts [source] on the standby under [key], unless [WarmStandby.request] skips it. */
    fun warm(
        key: String,
        source: PlayerSource,
    ) {
        if (released) return
        when (state.request(key)) {
            // WarmStandby logs the skip.
            is Request.Skip -> {
                if (state.disabled) releaseStandby("disabled")
            }

            Request.Start -> {
                val slot = spare ?: buildStandby()
                standbySource = source
                prepareStandby(slot, source)
            }
        }
    }

    /** Drops the standby if it is held for [key]; the player stays for the next warm. */
    fun drop(key: String) {
        if (state.held != key) return
        stopStandby()
        state.dropped("dropped")
    }

    /**
     * A memory trim at [level], as [WarmStandby.trim] decides: a running level below [WarmConfig.warmTrimLevel] only
     * logs; from it up the standby is released and warming paused for this player's life; the UI-hidden level and
     * above release the standby without pausing. The running levels are deprecated from API 34 (none are sent there);
     * the Fire TVs send them, and from 34 the higher levels still release the standby.
     */
    fun trim(level: Int) {
        if (state.trim(level) != TrimAction.Ignored) releaseStandby("trim")
    }

    /** Releases the standby (and any codec it holds); nothing is warmed after it. The front is the player's. */
    fun release() {
        released = true
        releaseStandby("released")
    }

    /**
     * The swap decision for a play of [source] with the handle [key]. The handle is bound to the source: a standby
     * holding [key] but built from another source ([sameStandbySource]: the start position and the handle aside) is a
     * [WarmReason.Mismatch], and the play is cold. On a swap the surface moves to the standby, which becomes the
     * [front], and the [Taken.handover] is set; otherwise it is null, and a held standby the play could not use is
     * dropped.
     */
    fun take(
        key: String,
        source: PlayerSource,
    ): Taken {
        val decision = state.decide(key, sameStandbySource(standbySource, source))
        val next = spare
        val reason =
            when {
                !decision.swap -> decision.reason
                next == null || next.exo.playerError != null -> WarmReason.Error
                next.exo.playbackState != Player.STATE_READY -> WarmReason.NotReady
                else -> WarmReason.None
            }
        if (reason != WarmReason.None || next == null) {
            if (decision.swap) {
                // decide() thought it ready, but the player is not: a failure of this standby, toward the breaker.
                WarmLog.line("fallback reason=${reason.label} ageMs=${decision.ageMs} ${WarmLog.memoryFields()}")
                state.fail(reason)
            }
            return Taken(coldAt(reason, decision), null)
        }
        val detachMs = moveSurfaceTo(next)
        state.swapped()
        return Taken(
            WarmOutcome(true, WarmReason.None, decision.ageMs, decision.readyMs, detachMs),
            Handover(key, decision.ageMs, detachMs),
        )
    }

    /** Builds the second player, the standby; it never has a surface while it waits. */
    private fun buildStandby(): Slot {
        val slot = host.newSlot()
        slot.exo.addAnalyticsListener(standbyAnalytics)
        spare = slot
        WarmLog.line("standby built ${WarmLog.memoryFields()}")
        return slot
    }

    /**
     * Prepares [source] on [slot] for the current [WarmStandby.epoch]: muted, paused, no text, audio only if
     * [WarmStandby.audioOn], buffer held. The media id carries the epoch, so the slot's events name the start they
     * belong to. A standby that is not idle is stopped first, which releases its codec only because the standby is
     * never in foreground mode: never call `setForegroundMode(true)` on a slot's player.
     */
    private fun prepareStandby(
        slot: Slot,
        source: PlayerSource,
    ) {
        val epoch = state.epoch
        val audioOn = state.audioOn
        // A standby that holds media (a replaced one) is stopped first. Media3 keeps a flushable codec across a new
        // item (disabling a renderer only flushes it; a stop resets it), so no decoder would come up for this start
        // and the probe would take a working decoder for a missing one. The standby must never be put in foreground
        // mode (setForegroundMode(true)): there a stop keeps the renderers, and their codecs, as they are.
        if (slot.exo.playbackState != Player.STATE_IDLE) slot.exo.stop()
        slot.holding.set(true)
        slot.exo.volume = 0f
        slot.exo.playWhenReady = false
        slot.exo.trackSelectionParameters =
            slot.exo.trackSelectionParameters
                .buildUpon()
                .clearOverrides()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audioOn)
                .build()
        slot.exo.setMediaItem(mediaItem(source, WARM_ID + epoch), source.playerStart.inWholeMilliseconds)
        slot.exo.prepare()
        scheduleCheck()
        WarmLog.line(
            "prepare key=${state.held} startMs=${source.playerStart.inWholeMilliseconds} " +
                "audio=${if (audioOn) "on" else "off"}",
        )
    }

    /**
     * One check, exactly at the standby's next deadline ([WarmStandby.nextCheckInMs]): the ready timeout from the
     * start, then the decoder grace from ready to play. Moved whenever the standby's state changes; none once it is
     * ready or failed.
     */
    private fun scheduleCheck() {
        handler.removeCallbacks(standbyCheck)
        state.nextCheckInMs()?.let { handler.postDelayed(standbyCheck, it) }
    }

    /** Acts on the standby's verdict at a deadline; one still pending (a clock edge) is checked again at the next. */
    private fun checkStandby() {
        if (state.held == null) return
        when (state.evaluate()) {
            Verdict.Pending -> {
                scheduleCheck()
            }

            // Ready: nothing more to watch; the swap decision reads it.
            Verdict.Ready -> {
                return
            }

            Verdict.DecoderMissing -> {
                state.decoderMissing()
                host.decoderMissing()
                stopStandby()
                releaseStandby("disabled")
            }

            Verdict.TimedOut -> {
                state.fail(WarmReason.Timeout)
                stopStandby()
                if (state.disabled) releaseStandby("disabled")
            }
        }
    }

    /** An error on the standby's play [epoch]. */
    private fun onStandbyError(
        epoch: Int,
        error: PlaybackException,
    ) {
        val key = state.held ?: return
        when (state.onError(key, isAudioError(error), epoch)) {
            // An older start's error: the standby now is another.
            ErrorAction.Ignored -> {
                return
            }

            ErrorAction.RetryVideoOnly -> {
                WarmLog.line("retry key=$key audio=off")
                standbySource?.let { source -> spare?.let { prepareStandby(it, source) } }
            }

            ErrorAction.Dropped -> {
                stopStandby()
            }

            ErrorAction.Disabled -> {
                releaseStandby("disabled")
            }
        }
    }

    /** Ends the standby's play (its codecs go with it); the player stays for the next warm. */
    private fun stopStandby() {
        handler.removeCallbacks(standbyCheck)
        spare?.let {
            it.holding.set(false)
            it.exo.stop()
            it.exo.clearMediaItems()
        }
    }

    /** Frees the standby player, whatever it held. */
    private fun releaseStandby(reason: String) {
        handler.removeCallbacks(standbyCheck)
        standbySource = null
        val slot = spare
        if (state.held != null) {
            state.released(reason)
        } else if (slot != null) {
            WarmLog.line("standby released reason=$reason")
        }
        spare = null
        slot?.exo?.release()
    }

    /**
     * The cold outcome for [reason], dropping a held standby the play could not use. With warming off (switched off
     * at run time, or the breaker just opened) the standby player itself is freed, not only its codec: no standby
     * will be started again for this player, so keeping it would only hold memory.
     */
    private fun coldAt(
        reason: WarmReason,
        decision: Decision,
    ): WarmOutcome {
        if (state.disabled) {
            releaseStandby("disabled")
        } else if (state.held != null) {
            stopStandby()
            state.dropped(reason.label)
        }
        return WarmOutcome(false, reason, decision.ageMs, decision.readyMs, 0L)
    }

    /**
     * Moves the app's surface from the front player to [next] and makes [next] the front; the old front is stopped
     * and becomes the standby's player. The old front lets go of the surface first (Media3 blocks until its renderer
     * has), so the surface never has two producers. The roles' listeners move last, where the roles change, so every
     * event up to then (the old front's stop among them) reaches the role it was sent in. The order is the spike's,
     * proven on the Firestick. Returns how long the old front took to let go, in ms.
     */
    private fun moveSurfaceTo(next: Slot): Long {
        val old = front
        val began = clock()
        old.exo.clearVideoSurface()
        val detachMs = clock() - began
        // With a surface, Media3 moves the running codec onto it (MediaCodec.setOutputSurface): no new decoder.
        host.drawOn(next.exo)
        old.exo.stop()
        old.exo.clearMediaItems()
        handler.removeCallbacks(standbyCheck)
        standbySource = null
        old.exo.removeAnalyticsListener(host.frontAnalytics)
        old.exo.removeListener(host.frontListener)
        next.exo.removeAnalyticsListener(standbyAnalytics)
        next.exo.addAnalyticsListener(host.frontAnalytics)
        next.exo.addListener(host.frontListener)
        old.exo.addAnalyticsListener(standbyAnalytics)
        front = next
        spare = old
        return detachMs
    }

    /** The extra fields of the `ready` line: the key, frames the hidden decoder put out, the held buffer, the heap. */
    private fun readyFields(): String {
        val slot = spare
        val frames =
            slot?.exo?.videoDecoderCounters?.let {
                it.ensureUpdated()
                it.renderedOutputBufferCount + it.skippedOutputBufferCount + it.droppedBufferCount
            } ?: NO_COUNT
        val bufKb = (slot?.allocator?.totalBytesAllocated ?: 0) / KIB
        return "key=${state.held} framesDecoded=$frames standbyBufKb=$bufKb ${WarmLog.memoryFields()}"
    }

    /**
     * The standby's events, on the standby ExoPlayer only: they feed its warm-up. The media id names the start they
     * belong to; an event of anything else (an old front's late event, no item) is ignored.
     */
    private inner class StandbyAnalytics : AnalyticsListener {
        private fun epochOf(eventTime: AnalyticsListener.EventTime): Int? = mediaIdOf(eventTime)?.let(::warmEpochOf)

        override fun onPlaybackStateChanged(
            eventTime: AnalyticsListener.EventTime,
            state: Int,
        ) {
            val epoch = epochOf(eventTime) ?: return
            val warmState = this@StandbySlots.state
            if (state != Player.STATE_READY) return
            warmState.held?.let { warmState.onStateReady(it, epoch) }
            // The decoder grace starts now.
            scheduleCheck()
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            val epoch = epochOf(eventTime) ?: return
            state.held?.let { state.onDecoderUp(it, initializationDurationMs, epoch) }
            // Ready now (no check left), or still waiting for READY.
            scheduleCheck()
        }

        /**
         * A codec Media3 kept for this start's format ([DecoderReuseEvaluation] with a reuse result) is a decoder
         * that is up, though no decoder-initialized event comes for it: counted as up with no init time, so a reuse
         * can never read as a missing decoder. No evaluation (no codec yet) or a refused reuse (a new codec, which
         * reports itself) says nothing. Same start check as [onVideoDecoderInitialized].
         */
        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            val result = decoderReuseEvaluation?.result ?: DecoderReuseEvaluation.REUSE_RESULT_NO
            val epoch = epochOf(eventTime)
            if (epoch == null || result == DecoderReuseEvaluation.REUSE_RESULT_NO) return
            state.held?.let { state.onDecoderUp(it, 0L, epoch) }
            scheduleCheck()
        }

        override fun onPlayerError(
            eventTime: AnalyticsListener.EventTime,
            error: PlaybackException,
        ) {
            val epoch = epochOf(eventTime)
            if (epoch != null && PlaybackLog.on) {
                PlaybackLog.line(
                    "playerError role=standby epoch=$epoch code=${error.errorCodeName} " +
                        "audioError=${isAudioError(error)} err=${PlaybackLog.failureText(error)}",
                )
            }
            epoch?.let { onStandbyError(it, error) }
        }
    }

    private companion object {
        const val NO_COUNT = -1
        const val KIB = 1_024

        /** The media id prefix of a standby's item; the start's epoch follows it. Never a generation. */
        const val WARM_ID = "warm-"

        /** The standby epoch in [mediaId], or null when it is not a standby's item. */
        fun warmEpochOf(mediaId: String): Int? {
            val warmed = mediaId.startsWith(WARM_ID)
            return if (warmed) mediaId.drop(WARM_ID.length).toIntOrNull() else null
        }

        /** An audio output or audio decoder error: the standby retries without audio. */
        fun isAudioError(error: PlaybackException): Boolean {
            val renderer = error as? ExoPlaybackException
            return error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
                (
                    renderer?.type == ExoPlaybackException.TYPE_RENDERER &&
                        MimeTypes.isAudio(renderer.rendererFormat?.sampleMimeType)
                )
        }
    }
}
