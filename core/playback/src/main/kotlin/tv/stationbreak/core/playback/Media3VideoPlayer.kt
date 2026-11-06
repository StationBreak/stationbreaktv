// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.content.ComponentCallbacks2
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.upstream.DefaultAllocator
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Media3 player of a host's lifetime. Create it on the thread whose looper should own the player (the main
 * thread); [play], [stop] and [release] may be called from any thread and run on that looper. [buffering] sets how
 * much media is buffered before a play starts. Media is read through [dataSource], which the app builds from its
 * credentialed OkHttp client, so this module needs no HTTP client of its own. [clock] stamps the events. No URI or
 * message is ever logged or put in an event: only class names and HTTP status codes.
 *
 * Two slots (spec 24): the front ExoPlayer, which draws on the app's surface and reports [events], and a standby,
 * built on the first [warm] that is not skipped, which prepares the next channel hidden, muted and paused with its
 * buffer held at [WarmConfig.warmHoldBufferMs]. A [play] whose [PlayerSource.warmKey] names a ready standby hands the
 * surface over to it and the two swap roles; every other play (no key, another key, not ready, failed, switched off)
 * is the ordinary play on the front, as before. [warm] holds the tunables. The standby is released on [release], on a
 * memory trim ([trimMemory]) and when warming is disabled; [stop] ends only the front's play.
 *
 * Track override (direct play only, [PlayerSource.directPlayTracks]): the audio and the in-file subtitle are picked by
 * their position among the file's tracks of the same kind, taken from the server's stream list, because Media3's
 * track groups carry no server index. The pick is then checked against the language and codec the server reported
 * ([lastPickVerified]). Limits: a container whose track order differs from the server's stream order, or tracks that
 * Media3 merges or drops (an unsupported audio format is a group but is not selectable), are picked wrongly; when the
 * check fails the position's pick is kept and nothing more is reported. A position with no track picks nothing and
 * leaves Media3's own default. A null subtitle pick with no separate subtitle turns text tracks off.
 */
@UnstableApi
@Suppress("TooManyFunctions", "LargeClass") // The one player: its two slots, transport and picks stay together.
class Media3VideoPlayer(
    private val context: Context,
    private val dataSource: DataSource.Factory,
    private val clock: () -> Long,
    private val buffering: PlaybackBuffering = PlaybackBuffering(),
    private val warm: WarmConfig = WarmConfig(),
) : VideoPlayer {
    init {
        // Before ExoPlayer exists: Media3's own error logs can quote a playlist line with a token.
        silenceMedia3Logging()
    }

    /**
     * One ExoPlayer and what is its own: its media buffer, its load counter, and its load control, which stops loading
     * at the hold while the player is a waiting standby ([holding]).
     */
    private class Slot(
        val exo: ExoPlayer,
        val allocator: DefaultAllocator,
        val loadCounter: LoadCounter,
        val holding: AtomicBoolean,
    ) {
        val skips = SkippedFrames(exo)
    }

    /** The player on screen. Swapped with [spare] by a warm hand-over. Written on the looper. */
    @Volatile private var front: Slot = buildSlot()

    /** The standby player, built on the first started warm; null before and once released. Written on the looper. */
    @Volatile private var spare: Slot? = null

    private val exo: ExoPlayer get() = front.exo
    private val loadCounter: LoadCounter get() = front.loadCounter
    private val skips: SkippedFrames get() = front.skips
    private val handler = Handler(front.exo.applicationLooper)

    /** The app's view the front draws on; a warm hand-over moves it. Looper only. */
    private var surfaceView: SurfaceView? = null

    /** A bare surface the front draws on instead, for this module's instrumented tests. Looper only. */
    private var rawSurface: Surface? = null

    // The `SBLIVE mem` line while a play is on the player (diagnostics, OOM of 2026-10-29).
    private val memory =
        MemorySampler(
            allocator = { front.allocator },
            schedule = { task, delayMs -> handler.postDelayed(task, delayMs) },
            cancel = handler::removeCallbacks,
            playing = { current != null },
            bufferedMs = { exo.totalBufferedDuration },
            log = { android.util.Log.i(MEMORY_LOG_TAG, it) },
        )

    /**
     * The player's events. The buffer is 64 and drops the oldest when full (a collector that stalls loses events): a
     * dropped `SeekDone` would surface as a SeekTimeout 20 s later, though the seek completed. The risk is accepted
     * while the buffer is ample for the few events a play produces.
     */
    private val flow =
        MutableSharedFlow<PlayerEvent>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val events: SharedFlow<PlayerEvent> = flow

    private val aspect = VideoAspectHolder()
    override val videoAspect: StateFlow<Float?> = aspect.aspect

    private val nextGeneration = AtomicLong()

    /** The standby's decisions (spec 24). Looper only. */
    private val warmState =
        WarmStandby(
            config = warm,
            deviceCannot = { deviceCannotWarm(context) },
            clock = clock,
            readyFields = ::readyFields,
            fallbackFields = WarmLog::memoryFields,
        )

    /** The source the standby was last started with, for a video-only retry. Looper only. */
    private var standbySource: PlayerSource? = null

    /** Polls [WarmStandby.evaluate] while a standby is being built. */
    private val standbyCheck = Runnable { checkStandby() }
    private val checkEveryMs = minOf(warm.warmDecoderGraceMs, warm.warmReadyTimeoutMs)

    /** The warm outcome of each recent play, by generation; written on the looper inside [play]. */
    private val outcomes = ConcurrentHashMap<Long, WarmOutcome>()

    /** The generation whose first frame on screen ends a warm hand-over, and when its play was called. Looper only. */
    private var swapShown: Pair<Long, Long>? = null

    /** Set by [release]: no standby is built or started after it. Looper only. */
    private var released = false

    /** The generation of the play now on the player; set on its looper, so events and plays agree. */
    @Volatile private var active = 0L
    private var current: PlayerSource? = null
    private var tracksApplied = false
    private var tracksReported = false

    /** The media id of the item the current play set (see [mediaItem]). */
    private var playId = ""
    private var hasPlayed = false

    /** A seek is under way: set by [seekTo], cleared by the `SeekDone`. Used on the player's looper. */
    private var seeking = false

    /**
     * What the looper last saw, for [position] and [seekable] off the looper: one immutable snapshot in one reference,
     * so a reader never mixes the values of two plays. Written on the looper only.
     */
    @Volatile private var published = Published.NONE
    private val audio = AudioSampler()
    private val av = AvRecorder()

    /** Whether the last direct-play audio pick matched the server's language and codec; null before any pick. */
    @Volatile var lastPickVerified: Boolean? = null
        private set

    init {
        listen(front)
    }

    /** The front Media3 player, for this module's instrumented tests. The app attaches a surface through [attach]. */
    internal val player: ExoPlayer get() = front.exo

    /** The standby Media3 player, if built, for this module's instrumented tests. */
    internal val standbyPlayer: ExoPlayer? get() = spare?.exo

    /** Builds one slot's ExoPlayer, with its own buffer, load counter and holding load control. */
    private fun buildSlot(): Slot {
        val allocator = PlaybackBuffering.mediaAllocator()
        val loadCounter = LoadCounter()
        val holding = AtomicBoolean(false)
        val loadControl =
            HoldingLoadControl(buffering.toLoadControl(allocator), warm.warmHoldBufferMs * US_PER_MS) { holding.get() }
        val exo =
            ExoPlayer
                .Builder(context)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(CountingMediaSourceFactory(dataSource, loadCounter))
                .build()
        return Slot(exo, allocator, loadCounter, holding)
    }

    /** Routes [slot]'s events: the front's become [PlayerEvent]s, the standby's feed its warm-up. */
    private fun listen(slot: Slot) {
        slot.exo.addAnalyticsListener(Analytics(slot))
        slot.exo.addListener(
            object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    // Only the picture on screen sets the shape: a hidden standby's size is not the screen's.
                    if (slot === front) aspect.onVideoSize(videoSize)
                }
            },
        )
    }

    override fun attach(surface: SurfaceView?) =
        onPlayerThread {
            surfaceView = surface
            rawSurface = null
            drawOn(exo)
        }

    /** Draws the front on [surface] instead of a view; for this module's instrumented tests. */
    internal fun attachSurface(surface: Surface?) =
        onPlayerThread {
            surfaceView = null
            rawSurface = surface
            drawOn(exo)
        }

    /** Puts the attached output (view or bare surface) on [target]; with none, clears [target]'s. Looper only. */
    private fun drawOn(target: ExoPlayer) {
        val view = surfaceView
        val raw = rawSurface
        when {
            view != null -> target.setVideoSurfaceView(view)
            raw != null -> target.setVideoSurface(raw)
            else -> target.clearVideoSurface()
        }
    }

    override fun play(source: PlayerSource): Long {
        val generation = nextGeneration.incrementAndGet()
        val calledAt = clock()
        onPlayerThread {
            // Spec 24: a ready standby for this source's key takes the surface first and becomes the front.
            outcomes.keys.removeAll { it <= generation - KEPT_OUTCOMES }
            val key = source.warmKey
            if (key == null) outcomes[generation] = WarmOutcome(false, WarmReason.NoHandle, 0L, 0L, 0L)
            val swap = key?.let { takeStandby(it, generation) }
            active = generation
            // In the same step as `active`, so a reader on another thread never sees the old play under the new number.
            published = Published(generation, source.playerStart.inWholeMilliseconds, false)
            av.begin(generation, calledAt)
            current = source
            tracksApplied = false
            tracksReported = false
            skips.reset()
            loadCounter.reset(generation)
            hasPlayed = false
            seeking = false
            audio.reset()
            lastPickVerified = null
            playId = generation.toString()
            if (swap != null) {
                startSwapped(source, generation, calledAt, swap)
            } else {
                exo.trackSelectionParameters =
                    exo.trackSelectionParameters
                        .buildUpon()
                        .clearOverrides()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .build()
                exo.setMediaItem(mediaItem(source, playId), source.playerStart.inWholeMilliseconds)
                exo.prepare()
                exo.playWhenReady = true
            }
            memory.start()
            publish()
        }
        return generation
    }

    override fun stop() =
        onPlayerThread {
            current = null
            // The closing audio sample, taken before the stop resets the position.
            if (audio.takeClosingSample()) {
                emit(PlayerEvent.AudioAdvanced(exo.currentPosition.milliseconds, active))
            }
            active = 0L
            seeking = false
            exo.stop()
            exo.clearMediaItems()
            publish()
        }

    override fun warm(
        key: String,
        source: PlayerSource,
    ) = onPlayerThread { startWarm(key, source) }

    override fun dropWarm(key: String) =
        onPlayerThread {
            if (warmState.held != key) return@onPlayerThread
            stopStandby()
            warmState.dropped("dropped")
        }

    override fun warmOutcome(generation: Long): WarmOutcome? = outcomes[generation]

    // TRIM_MEMORY_RUNNING_MODERATE is deprecated from API 34 (no running levels sent there); the Fire TVs send them,
    // and from 34 the higher levels still release the standby.
    @Suppress("DEPRECATION")
    override fun trimMemory(level: Int) =
        onPlayerThread {
            if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) return@onPlayerThread
            warmState.pauseForVisit()
            releaseStandby("trim")
        }

    /** Starts [source] on the standby under [key], unless [WarmStandby.request] skips it. Looper only. */
    private fun startWarm(
        key: String,
        source: PlayerSource,
    ) {
        if (released) return
        when (val request = warmState.request(key)) {
            is Request.Skip -> {
                WarmLog.line("skipped key=$key reason=${request.reason}")
                if (warmState.disabled) releaseStandby("disabled")
            }

            Request.Start -> {
                val slot = spare ?: buildStandby()
                standbySource = source
                prepareStandby(slot, source)
            }
        }
    }

    /** Builds the second player, the standby; it never has a surface while it waits. Looper only. */
    private fun buildStandby(): Slot {
        val slot = buildSlot()
        listen(slot)
        spare = slot
        WarmLog.line("standby built ${WarmLog.memoryFields()}")
        return slot
    }

    /**
     * Prepares [source] on [slot] for the current [WarmStandby.epoch]: muted, paused, no text, audio only if
     * [WarmStandby.audioOn], buffer held. The media id carries the epoch, so the slot's events name the start they
     * belong to. Looper only.
     */
    private fun prepareStandby(
        slot: Slot,
        source: PlayerSource,
    ) {
        val epoch = warmState.epoch
        val audioOn = warmState.audioOn
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
        handler.removeCallbacks(standbyCheck)
        handler.postDelayed(standbyCheck, checkEveryMs)
        WarmLog.line(
            "prepare key=${warmState.held} startMs=${source.playerStart.inWholeMilliseconds} " +
                "audio=${if (audioOn) "on" else "off"}",
        )
    }

    /** Acts on the standby's verdict while it is being built; polls again while it is pending. Looper only. */
    private fun checkStandby() {
        if (warmState.held == null) return
        when (warmState.evaluate()) {
            Verdict.Pending -> {
                handler.postDelayed(standbyCheck, checkEveryMs)
            }

            // Ready: nothing more to watch; the swap decision reads it.
            Verdict.Ready -> {
                return
            }

            Verdict.DecoderMissing -> {
                warmState.decoderMissing()
                stopStandby()
                releaseStandby("disabled")
            }

            Verdict.TimedOut -> {
                warmState.fail(WarmReason.Timeout)
                stopStandby()
                if (warmState.disabled) releaseStandby("disabled")
            }
        }
    }

    /** An error on the standby's play [epoch]. Looper only. */
    private fun onStandbyError(
        epoch: Int,
        error: PlaybackException,
    ) {
        val key = warmState.held ?: return
        when (warmState.onError(key, isAudioError(error), epoch)) {
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

    /** Ends the standby's play (its codecs go with it); the player stays for the next warm. Looper only. */
    private fun stopStandby() {
        handler.removeCallbacks(standbyCheck)
        spare?.let {
            it.holding.set(false)
            it.exo.stop()
            it.exo.clearMediaItems()
        }
    }

    /** Frees the standby player, whatever it held. Looper only. */
    private fun releaseStandby(reason: String) {
        handler.removeCallbacks(standbyCheck)
        standbySource = null
        val slot = spare
        if (warmState.held != null) {
            warmState.released(reason)
        } else if (slot != null) {
            WarmLog.line("standby released reason=$reason")
        }
        spare = null
        slot?.exo?.release()
    }

    /**
     * The swap decision for [key] at the play [generation], recorded as its [WarmOutcome]. On a swap the surface
     * moves to the standby, which becomes the front, and its [Handover] is returned; otherwise null, and a held
     * standby the play could not use is dropped. Looper only.
     */
    private fun takeStandby(
        key: String,
        generation: Long,
    ): Handover? {
        val decision = warmState.decide(key)
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
                WarmLog.line("fallback reason=${reason.label} ageMs=${decision.ageMs} ${WarmLog.memoryFields()}")
            }
            coldAt(generation, reason, decision)
            return null
        }
        val detachMs = moveSurfaceTo(next)
        warmState.swapped()
        outcomes[generation] = WarmOutcome(true, WarmReason.None, decision.ageMs, decision.readyMs, detachMs)
        return Handover(key, decision.ageMs, detachMs)
    }

    /** Records play [generation] as cold for [reason], and drops a held standby it could not use. Looper only. */
    private fun coldAt(
        generation: Long,
        reason: WarmReason,
        decision: Decision,
    ) {
        if (warmState.held != null) {
            stopStandby()
            warmState.dropped(reason.label)
        }
        outcomes[generation] = WarmOutcome(false, reason, decision.ageMs, decision.readyMs, 0L)
    }

    /** What [startSwapped] logs of the hand-over. */
    private class Handover(
        val key: String,
        val ageMs: Long,
        val detachMs: Long,
    )

    /**
     * Moves the app's surface from the front player to [next] and makes [next] the front; the old front is stopped
     * and becomes the standby's player. The old front lets go of the surface first (Media3 blocks until its renderer
     * has), so the surface never has two producers. Returns how long that took, in ms. Looper only.
     */
    private fun moveSurfaceTo(next: Slot): Long {
        val old = front
        val began = clock()
        old.exo.clearVideoSurface()
        val detachMs = clock() - began
        // With a surface, Media3 moves the running codec onto it (MediaCodec.setOutputSurface): no new decoder.
        drawOn(next.exo)
        old.exo.stop()
        old.exo.clearMediaItems()
        handler.removeCallbacks(standbyCheck)
        standbySource = null
        front = next
        spare = old
        // The new front's shape, if it already knows it; else the last one stays until it reports one.
        aspect.onVideoSize(next.exo.videoSize)
        return detachMs
    }

    /** Starts the new front (the standby that was) from [source]'s start, with its picks and its sound. Looper only. */
    private fun startSwapped(
        source: PlayerSource,
        generation: Long,
        calledAt: Long,
        handover: Handover,
    ) {
        val parameters =
            exo.trackSelectionParameters
                .buildUpon()
                .clearOverrides()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        if (source.directPlayTracks) {
            picksOnto(parameters, source, exo.currentTracks)
            tracksApplied = true
        }
        exo.trackSelectionParameters = parameters.build()
        val startMs = source.playerStart.inWholeMilliseconds
        val driftMs = exo.currentPosition - startMs
        val seek = abs(driftMs) > warm.warmSeekToleranceMs
        if (seek) exo.seekTo(startMs)
        front.holding.set(false)
        exo.volume = 1f
        exo.playWhenReady = true
        // The tracks were known before this play: they are ready at its start, and no load paid for them.
        tracksReported = true
        emit(PlayerEvent.TracksReady(clock(), generation, null))
        swapShown = generation to calledAt
        WarmLog.line(
            "swap key=${handover.key} ageMs=${handover.ageMs} detachMs=${handover.detachMs} driftMs=$driftMs " +
                "seek=$seek",
        )
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
        return "key=${warmState.held} framesDecoded=$frames standbyBufKb=$bufKb ${WarmLog.memoryFields()}"
    }

    override fun pause(generation: Long) =
        onPlayerThread {
            if (generation <= 0 || active != generation || !exo.playWhenReady) return@onPlayerThread
            exo.playWhenReady = false
            emit(PlayerEvent.Paused(generation, clock(), exo.currentPosition))
        }

    override fun resume(generation: Long) =
        onPlayerThread {
            if (generation <= 0 || active != generation || exo.playWhenReady) return@onPlayerThread
            emit(PlayerEvent.Resumed(generation, clock()))
            exo.playWhenReady = true
        }

    override fun seekTo(
        generation: Long,
        position: Duration,
    ) = onPlayerThread {
        if (generation <= 0 || active != generation) return@onPlayerThread
        val to = position.inWholeMilliseconds
        // A seek already pending is coalesced: the target moves, and its one SeekDone carries the final position.
        if (!seeking) {
            // Set first: Media3 may report the finish from inside the seek call.
            seeking = true
            emit(PlayerEvent.Seeking(generation, clock(), exo.currentPosition, to))
        }
        exo.seekTo(to)
        publish()
    }

    override fun position(generation: Long): Duration? {
        val ms = snapshot(generation)?.positionMs ?: return null
        return if (ms == NO_POSITION) null else ms.milliseconds
    }

    override suspend fun currentPosition(generation: Long): Duration? =
        suspendCancellableCoroutine { continuation ->
            onPlayerThread {
                val live = if (generation <= 0 || active != generation) null else exo.currentPosition.milliseconds
                continuation.resume(live)
            }
        }

    override fun seekable(generation: Long): Boolean = snapshot(generation)?.seekable == true

    /** The published snapshot if it is [generation]'s, else null. On the looper it is refreshed first. */
    private fun snapshot(generation: Long): Published? {
        if (generation <= 0) return null
        if (Looper.myLooper() == handler.looper && active == generation) publish()
        return published.takeIf { it.generation == generation }
    }

    override fun release() =
        onPlayerThread {
            if (released) return@onPlayerThread
            released = true
            current = null
            active = 0L
            seeking = false
            published = Published.NONE
            memory.stop()
            // Both slots: the standby (and any codec it holds) first, then the front.
            releaseStandby("released")
            front.exo.release()
        }

    /** Sends [event] and refreshes what [position] and [seekable] answer from other threads. Looper only. */
    private fun emit(event: PlayerEvent) {
        // Published first, so a collector on another thread sees values at least as fresh as the event.
        publish()
        flow.tryEmit(event)
    }

    private fun publish() {
        published =
            if (active == 0L) Published.NONE else Published(active, exo.currentPosition, exo.isCurrentMediaItemSeekable)
    }

    /** What the looper saw of play [generation]; [Published.NONE] (generation 0) for none. */
    private data class Published(
        val generation: Long,
        val positionMs: Long,
        val seekable: Boolean,
    ) {
        companion object {
            val NONE = Published(0L, NO_POSITION, false)
        }
    }

    /** The seek is over: one `SeekDone`. Nothing when no seek is under way. */
    private fun finishSeek() {
        if (!seeking) return
        seeking = false
        emit(PlayerEvent.SeekDone(active, clock(), exo.currentPosition))
    }

    private fun onPlayerThread(block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) block() else handler.post(block)
    }

    private fun mediaItem(
        source: PlayerSource,
        mediaId: String,
    ): MediaItem {
        // The media id names the play, so a load event can be told from one of the item before it.
        val builder = MediaItem.Builder().setMediaId(mediaId).setUri(Uri.parse(source.url))
        if (source.isHls) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        builder.setSubtitleConfigurations(
            source.subtitles.map { subtitle ->
                MediaItem.SubtitleConfiguration
                    .Builder(Uri.parse(subtitle.url))
                    .setMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage(subtitle.language)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            },
        )
        return builder.build()
    }

    private fun applyPicks(
        source: PlayerSource,
        tracks: Tracks,
    ) {
        val parameters = exo.trackSelectionParameters.buildUpon()
        picksOnto(parameters, source, tracks)
        exo.trackSelectionParameters = parameters.build()
    }

    /** Puts [source]'s audio and subtitle picks among [tracks] onto [parameters]. */
    private fun picksOnto(
        parameters: TrackSelectionParameters.Builder,
        source: PlayerSource,
        tracks: Tracks,
    ) {
        source.audioPick?.let { pick ->
            val group = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.getOrNull(pick.position)
            if (group != null) {
                parameters.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                lastPickVerified = matches(pick, group.getTrackFormat(0))
            }
        }
        val subtitlePick = source.subtitlePick
        if (subtitlePick != null) {
            tracks.groups
                .filter { it.type == C.TRACK_TYPE_TEXT }
                .getOrNull(subtitlePick.position)
                ?.let { parameters.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
        } else if (source.subtitles.isEmpty()) {
            parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }
    }

    private fun matches(
        pick: TrackPick,
        format: androidx.media3.common.Format,
    ): Boolean = languageMatches(pick.language, format.language) && codecMatches(pick.codec, format.sampleMimeType)

    private fun languageMatches(
        wanted: String?,
        actual: String?,
    ): Boolean {
        if (wanted == null || actual == null) return true
        return runCatching { iso3(wanted) == iso3(actual) }.getOrDefault(false)
    }

    private fun iso3(code: String): String = Locale.forLanguageTag(code).isO3Language

    private fun codecMatches(
        wanted: String?,
        mime: String?,
    ): Boolean {
        val expected = wanted?.lowercase()?.let(CODEC_MIMES::get) ?: return true
        return mime != null && mime == expected
    }

    /**
     * The events of [slot]'s player. Only the front player's become [PlayerEvent]s; the standby's feed its warm-up
     * (the media id names the start they belong to), and an old front's late events are dropped.
     */
    @Suppress("TooManyFunctions") // One override per Media3 event the player reports.
    private inner class Analytics(
        private val slot: Slot,
    ) : AnalyticsListener {
        private val onFront: Boolean get() = slot === front
        private val isStandby: Boolean get() = slot === spare

        /** [eventTime]'s standby start, or null when it is not one (no item, the front's own, an old front's). */
        private fun standbyEpoch(eventTime: AnalyticsListener.EventTime): Int? =
            if (isStandby) mediaIdOf(eventTime)?.let(::warmEpochOf) else null

        override fun onTracksChanged(
            eventTime: AnalyticsListener.EventTime,
            tracks: Tracks,
        ) {
            if (onFront) frontTracks(tracks)
        }

        private fun frontTracks(tracks: Tracks) {
            if (!tracksReported && tracks.groups.isNotEmpty()) {
                tracksReported = true
                emit(PlayerEvent.TracksReady(clock(), active, loadCounter.close(containerMime(tracks))))
            }
            val source = current ?: return
            if (!source.directPlayTracks || tracksApplied || tracks.groups.isEmpty()) return
            tracksApplied = true
            applyPicks(source, tracks)
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long,
        ) {
            if (!onFront) return
            val shown = swapShown
            if (shown != null && shown.first == active) {
                // Only a frame on the app's surface ends the hand-over (not a late one from the hidden output).
                val onScreen = surfaceView?.holder?.surface ?: rawSurface
                if (onScreen != null && output !== onScreen) return
                swapShown = null
                WarmLog.line("swap shown playToFrameMs=${clock() - shown.second}")
            }
            emit(PlayerEvent.FirstFrame(clock(), active, skips.sincePlay()))
        }

        override fun onIsPlayingChanged(
            eventTime: AnalyticsListener.EventTime,
            isPlaying: Boolean,
        ) {
            if (!onFront || !isPlaying) return
            hasPlayed = true
            val timings = av.finish(eventGeneration(eventTime, active))
            emit(PlayerEvent.Playing(clock(), exo.currentPosition.milliseconds, active, timings))
        }

        override fun onPlaybackStateChanged(
            eventTime: AnalyticsListener.EventTime,
            state: Int,
        ) {
            standbyEpoch(eventTime)?.let { epoch ->
                if (state == Player.STATE_READY) warmState.held?.let { warmState.onStateReady(it, epoch) }
            }
            if (!onFront) return
            if (state == Player.STATE_READY) av.ready(eventGeneration(eventTime, active), clock())
            // A seek ends at READY, or at the end of the stream (the target was reached: the end). IDLE drops it.
            if (state == Player.STATE_READY || state == Player.STATE_ENDED) finishSeek()
            if (state == Player.STATE_IDLE) seeking = false
            // A rebuffer: buffering again after playback had begun. One that follows a seek is the seek's own.
            if (state == Player.STATE_BUFFERING && hasPlayed && !seeking) {
                emit(PlayerEvent.Stalled(active, clock(), exo.currentPosition, exo.totalBufferedDuration))
            }
            if (state == Player.STATE_ENDED) emit(PlayerEvent.Ended(active))
        }

        override fun onPositionDiscontinuity(
            eventTime: AnalyticsListener.EventTime,
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (!onFront) return
            // A seek inside the buffer, or to the current position, never leaves READY: no state change would end it.
            if (reason == Player.DISCONTINUITY_REASON_SEEK && exo.playbackState == Player.STATE_READY) finishSeek()
        }

        override fun onCues(
            eventTime: AnalyticsListener.EventTime,
            cueGroup: CueGroup,
        ) {
            if (!onFront) return
            emit(PlayerEvent.Cues(cueGroup.cues.size, active))
        }

        override fun onAudioPositionAdvancing(
            eventTime: AnalyticsListener.EventTime,
            playoutStartSystemTimeMs: Long,
        ) {
            if (!onFront) return
            audio.advancing()
            av.audioAdvancing(eventGeneration(eventTime, active), clock())
            emit(PlayerEvent.AudioAdvanced(exo.currentPosition.milliseconds, active))
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            standbyEpoch(eventTime)?.let { epoch ->
                warmState.held?.let { warmState.onDecoderUp(it, initializationDurationMs, epoch) }
            }
            if (!onFront) return
            av.videoDecoder(eventGeneration(eventTime, active), clock(), initializationDurationMs)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            if (!onFront) return
            av.audioDecoder(eventGeneration(eventTime, active), clock(), initializationDurationMs)
        }

        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: AudioSink.AudioTrackConfig,
        ) {
            if (!onFront) return
            av.audioTrack(eventGeneration(eventTime, active), clock())
        }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            if (!onFront) return
            av.underrun(eventGeneration(eventTime, active))
        }

        override fun onPlayerError(
            eventTime: AnalyticsListener.EventTime,
            error: PlaybackException,
        ) {
            standbyEpoch(eventTime)?.let { onStandbyError(it, error) }
            if (!onFront) return
            // The play ends here: a pending seek will not finish, and says nothing.
            seeking = false
            emit(playerFailure(error, active, clock(), exo.currentPosition, exo.totalBufferedDuration))
        }
    }

    private companion object {
        const val EVENT_BUFFER = 64
        const val NO_POSITION = -1L
        const val NO_COUNT = -1
        const val US_PER_MS = 1_000L
        const val KIB = 1_024

        /** The media id prefix of a standby's item; the start's epoch follows it. Never a generation. */
        const val WARM_ID = "warm-"

        /** How many plays' warm outcomes are kept for [warmOutcome]. */
        const val KEPT_OUTCOMES = 16L

        /** The no-surface decoder list's answer, once per process: reading it builds a renderer. */
        @Volatile var deviceCannot: Boolean? = null

        fun deviceCannotWarm(context: Context): Boolean {
            val known = deviceCannot
            return known ?: NoSurfaceDecoders.deviceCannot(context).also { deviceCannot = it }
        }

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

        /** The tag live TV's measurement lines use, so a device run collects this line with them. */
        const val MEMORY_LOG_TAG = "SBLIVE"

        /** The Media3 sample MIME type of each codec name the server uses; unlisted codecs are not checked. */
        val CODEC_MIMES =
            mapOf(
                "aac" to MimeTypes.AUDIO_AAC,
                "ac3" to MimeTypes.AUDIO_AC3,
                "eac3" to MimeTypes.AUDIO_E_AC3,
                "mp3" to MimeTypes.AUDIO_MPEG,
                "opus" to MimeTypes.AUDIO_OPUS,
                "vorbis" to MimeTypes.AUDIO_VORBIS,
                "flac" to MimeTypes.AUDIO_FLAC,
                "truehd" to MimeTypes.AUDIO_TRUEHD,
                "dts" to MimeTypes.AUDIO_DTS,
            )
    }
}

/** The container MIME type the extractor put on the first video (else any) track; null when none says. */

private fun containerMime(tracks: Tracks): String? {
    val groups = tracks.groups.filter { it.length > 0 }.sortedBy { if (it.type == C.TRACK_TYPE_VIDEO) 0 else 1 }
    return groups.firstNotNullOfOrNull { it.getTrackFormat(0).containerMimeType }
}

/**
 * The video decoder's count of skipped output buffers (frames decoded and dropped to reach an exact seek position),
 * taken per play: [reset] notes where the count stood when a play began, and [sincePlay] reads the frames skipped
 * since. The counters live as long as the decoder, so they may carry an earlier play's count; a new counters object
 * (the decoder was re-enabled) starts at zero. Used on the player's looper.
 */
@UnstableApi
private class SkippedFrames(
    private val exo: ExoPlayer,
) {
    private var base: DecoderCounters? = null
    private var baseCount = 0L

    fun reset() {
        base = exo.videoDecoderCounters
        baseCount = count(base)
    }

    /** Frames skipped since [reset]; null when the player has no video decoder counters. */
    fun sincePlay(): Long? {
        val counters = exo.videoDecoderCounters ?: return null
        return count(counters) - if (counters === base) baseCount else 0L
    }

    private fun count(counters: DecoderCounters?): Long {
        counters?.ensureUpdated()
        return counters?.skippedOutputBufferCount?.toLong() ?: 0L
    }
}

/** The media id of [eventTime]'s item; null when the event has no item. */
@UnstableApi
private fun mediaIdOf(eventTime: AnalyticsListener.EventTime): String? {
    val timeline = eventTime.timeline
    if (timeline.isEmpty || eventTime.windowIndex >= timeline.windowCount) return null
    return timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId
}

/** The play an event belongs to: the media id names it; an event with no item (or a standby's item) is [active]'s. */
@UnstableApi
private fun eventGeneration(
    eventTime: AnalyticsListener.EventTime,
    active: Long,
): Long = mediaIdOf(eventTime)?.toLongOrNull() ?: active
