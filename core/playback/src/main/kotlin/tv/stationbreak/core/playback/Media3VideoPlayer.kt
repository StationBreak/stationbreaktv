// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
 * The one Media3 player, built once and reused for every play of its host's lifetime. Create it on the thread whose
 * looper should own the player (the main thread); [play], [stop] and [release] may be called from any thread and run
 * on that looper. [buffering] sets how much media is buffered before a play starts. Media is read through
 * [dataSource], which the app builds from its credentialed OkHttp client, so
 * this module needs no HTTP client of its own. [clock] stamps the events. No URI or message is ever logged or put in
 * an event: only class names and HTTP status codes.
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
@Suppress("TooManyFunctions") // The one player: its transport and its picks stay together.
class Media3VideoPlayer(
    private val context: Context,
    private val dataSource: DataSource.Factory,
    private val clock: () -> Long,
    private val buffering: PlaybackBuffering = PlaybackBuffering(),
) : VideoPlayer {
    init {
        // Before ExoPlayer exists: Media3's own error logs can quote a playlist line with a token.
        silenceMedia3Logging()
    }

    /**
     * What one ExoPlayer is built with and owns: its media buffer, its load counter and its load control, which stops
     * loading early while the player is a waiting standby ([holding], spike #563).
     */
    private class SlotParts(
        buffering: PlaybackBuffering,
        dataSource: DataSource.Factory,
    ) {
        val allocator: DefaultAllocator = PlaybackBuffering.mediaAllocator()
        val loadCounter = LoadCounter()
        val holding = AtomicBoolean(false)
        val loadControl =
            HoldingLoadControl(
                buffering.toLoadControl(allocator),
                WarmSettings.HOLD_BUFFER_MS * US_PER_MS,
            ) { holding.get() }
        val factory = CountingMediaSourceFactory(dataSource, loadCounter)

        fun builder(context: Context): ExoPlayer.Builder =
            ExoPlayer.Builder(context).setLoadControl(loadControl).setMediaSourceFactory(factory)

        fun slot(
            name: String,
            exo: ExoPlayer,
        ) = Slot(name, exo, allocator, loadCounter, holding)
    }

    /** One ExoPlayer and what is its own. Spike #563 holds two: the one on screen ([front]) and a standby ([spare]). */
    private class Slot(
        val name: String,
        val exo: ExoPlayer,
        val allocator: DefaultAllocator,
        val loadCounter: LoadCounter,
        val holding: AtomicBoolean,
    ) {
        val skips = SkippedFrames(exo)
    }

    private val partsA = SlotParts(buffering, dataSource)

    // Spike #563: the player is built through the preload manager's builder, so the two share one playback thread,
    // renderers, track selector, bandwidth meter and load control, and a prepared source can be handed to the player.
    private val preloadStatuses = ConcurrentHashMap<Int, DefaultPreloadManager.PreloadStatus>()
    private val preloadBuilder =
        DefaultPreloadManager
            .Builder(
                context,
                TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
                    preloadStatuses[it] ?: DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
                },
            ).setMediaSourceFactory(partsA.factory)
            .setLoadControl(partsA.loadControl)

    /** The player the preload manager feeds; a preloaded source can only be played on it. */
    private val slotA: Slot = partsA.slot("a", preloadBuilder.buildExoPlayer(partsA.builder(context)))
    private val preloadManager = preloadBuilder.build()

    /** The player on screen. Swapped with [spare] by a warm hand-over (spike #563). Looper only. */
    private var front: Slot = slotA

    /** The standby player, built on the first warm request; null until then or once released. Looper only. */
    private var spare: Slot? = null

    private val exo: ExoPlayer get() = front.exo
    private val loadCounter: LoadCounter get() = front.loadCounter
    private val skips: SkippedFrames get() = front.skips
    private val handler = Handler(slotA.exo.applicationLooper)

    /** The surface the app attached; the front player draws on it, and a warm hand-over moves it. Looper only. */
    private var surfaceView: SurfaceView? = null

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

    private val nextGeneration = AtomicLong()

    /** One preload: the item it was made for, its ranking number, when it was asked for and when it was done. */
    private class PreloadEntry(
        val item: MediaItem,
        val rank: Int,
        val startedAt: Long,
    ) {
        @Volatile var completedAt: Long = NOT_DONE

        @Volatile var error: String? = null
    }

    /** The preloads held, by key. Written on the player's looper; read from any thread. */
    private val preloads = ConcurrentHashMap<String, PreloadEntry>()

    /** The preload whose source is on the player now; removed from the manager once the play ends. Looper only. */
    private var playedPreload: PreloadEntry? = null
    private var nextRank = 0

    init {
        preloadManager.addListener(
            object : PreloadManagerListener {
                override fun onCompleted(mediaItem: MediaItem) {
                    val key = mediaItem.mediaId.removePrefix(PRELOAD_ID)
                    val entry = preloads[key] ?: return
                    entry.completedAt = clock()
                    val setupMs = entry.completedAt - entry.startedAt
                    PreloadLog.line("prepared key=$key setupMs=$setupMs status=$STATUS_NAME")
                }

                override fun onError(error: PreloadException) {
                    val key = error.mediaItem.mediaId.removePrefix(PRELOAD_ID)
                    // Kept (marked) so the tune can say it failed; play() does not use it.
                    preloads[key]?.error = PreloadLog.describe(error)
                    PreloadLog.line("error key=$key ${PreloadLog.describe(error)}")
                }
            },
        )
    }

    /**
     * Spike #563: the standby's play: its key and source, when it began, and how far it got. Times are [clock] values;
     * [NOT_DONE] until reached. Written on the looper; read from any thread.
     */
    private class Warm(
        val key: String,
        val startedAt: Long,
    ) {
        @Volatile var decoderAt: Long = NOT_DONE

        @Volatile var decoderInitMs: Long = NOT_DONE

        @Volatile var stateReady = false

        @Volatile var readyAt: Long = NOT_DONE

        @Volatile var error: String? = null

        val ready: Boolean get() = readyAt != NOT_DONE && error == null

        /** True once: the player is ready and its video decoder is up, and it has not been marked ready or failed. */
        fun becameReady(): Boolean = readyAt == NOT_DONE && error == null && stateReady && decoderAt != NOT_DONE
    }

    /** The standby's play, if any. Written on the looper. */
    @Volatile private var warm: Warm? = null

    /** Set once the device showed it cannot keep a decoder warm without a surface, or failed too often. Looper only. */
    private var warmDisabled = false
    private var warmFailures = 0

    /** The generation whose first frame on screen is the end of a warm hand-over, and when its play was called. */
    private var swapShown: Pair<Long, Long>? = null

    private val warmTimeout =
        Runnable {
            val w = warm ?: return@Runnable
            if (w.ready) return@Runnable
            // No decoder at all in that time: most likely the device cannot start one without a surface. Stop trying.
            if (w.decoderAt == NOT_DONE) warmDisabled = true
            giveUpWarm(w, if (w.decoderAt == NOT_DONE) "timeoutNoDecoder" else "timeout")
        }

    private val decoderCheck =
        Runnable {
            val w = warm ?: return@Runnable
            if (w.stateReady && w.decoderAt == NOT_DONE && w.error == null) {
                // Media3 starts no codec on a player with no surface where the device needs its output-surface
                // workaround (no placeholder surface): this device cannot keep a decoder warm this way.
                warmDisabled = true
                giveUpWarm(w, "noDecoderWithoutSurface")
            }
        }

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
        slotA.exo.addAnalyticsListener(Analytics(slotA))
    }

    /** The Media3 player, for this module's instrumented tests. The app attaches a surface through [attach]. */
    internal val player: Player get() = exo

    override fun attach(surface: SurfaceView?) =
        onPlayerThread {
            surfaceView = surface
            if (surface == null) exo.clearVideoSurface() else exo.setVideoSurfaceView(surface)
        }

    @Suppress("LongMethod") // One play's set-up, with the warm hand-over beside the ordinary path.
    override fun play(source: PlayerSource): Long {
        val generation = nextGeneration.incrementAndGet()
        val calledAt = clock()
        onPlayerThread {
            // Spike #563: the standby holds this very source, ready: hand the surface over to it first.
            val standby = standbyFor(source)
            val detachMs = standby?.let { moveSurfaceToStandby() }
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
            val parameters =
                exo.trackSelectionParameters
                    .buildUpon()
                    .clearOverrides()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            playId = generation.toString()
            if (standby != null) {
                startStandby(standby, source, generation, calledAt, parameters, detachMs ?: NOT_DONE)
                memory.start()
                publish()
                return@onPlayerThread
            }
            exo.trackSelectionParameters = parameters.build()
            val preloaded = source.preloadKey?.let { preloads.remove(it) }
            val usable = preloaded?.takeIf { it.error == null }
            // Only the preload manager's own player can take its source; after a warm swap the front may be the other.
            val prepared = usable?.takeIf { front === slotA }?.let { preloadManager.getMediaSource(it.item) }
            if (source.preloadKey != null) {
                PreloadLog.line(
                    "handover key=${source.preloadKey} used=${prepared != null} entry=${preloaded != null} " +
                        "front=${front.name}",
                )
            }
            if (prepared != null) {
                // Spike #563: the prepared source, from where the live schedule is now.
                exo.setMediaSource(prepared, source.playerStart.inWholeMilliseconds)
            } else {
                exo.setMediaItem(mediaItem(source, playId), source.playerStart.inWholeMilliseconds)
            }
            releasePlayedPreload()
            if (preloaded != null && prepared == null) preloadManager.remove(preloaded.item)
            playedPreload = preloaded.takeIf { prepared != null }
            exo.prepare()
            exo.playWhenReady = true
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
            releasePlayedPreload()
            publish()
        }

    override fun preload(
        key: String,
        source: PlayerSource,
    ) {
        PreloadLog.line("preload called key=$key")
        onPlayerThread {
            // The warm switch was turned off: free the standby player's memory (the manager's own player stays).
            if (!WarmSettings.enabled()) releaseSpare("switchOff")
            startPreload(key, source)
        }
    }

    override fun warm(
        key: String,
        source: PlayerSource,
    ) = onPlayerThread { startWarm(key, source) }

    /** Prepares [source] on the standby, paused, muted, with no surface and a held buffer. Looper only. */
    private fun startWarm(
        key: String,
        source: PlayerSource,
    ) {
        val held = warm
        when {
            warmDisabled -> {
                WarmLog.line("skipped key=$key reason=disabled")
            }

            held?.key == key -> {
                WarmLog.line("skipped key=$key reason=alreadyWarm")
            }

            else -> {
                held?.let { dropWarm(it, "replaced") }
                val slot = spare ?: buildSpare()
                val audio = WarmSettings.audioWarm()
                val w = Warm(key, clock())
                warm = w
                slot.holding.set(true)
                slot.exo.volume = 0f
                slot.exo.playWhenReady = false
                slot.exo.trackSelectionParameters =
                    slot.exo.trackSelectionParameters
                        .buildUpon()
                        .clearOverrides()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audio)
                        .build()
                slot.exo.setMediaItem(mediaItem(source, WARM_ID + key), source.playerStart.inWholeMilliseconds)
                slot.exo.prepare()
                handler.removeCallbacks(warmTimeout)
                handler.postDelayed(warmTimeout, WarmSettings.READY_TIMEOUT_MS)
                WarmLog.line(
                    "prepare key=$key slot=${slot.name} startMs=${source.playerStart.inWholeMilliseconds} " +
                        "audio=${if (audio) "on" else "off"}",
                )
            }
        }
    }

    /** Builds the second player, the standby; it starts with no surface. Looper only. */
    private fun buildSpare(): Slot {
        val parts = SlotParts(buffering, dataSource)
        val slot = parts.slot("b", parts.builder(context).build())
        slot.exo.addAnalyticsListener(Analytics(slot))
        spare = slot
        WarmLog.line("standby built slot=b ${WarmLog.memoryFields()}")
        return slot
    }

    /** The standby, when it holds [source]'s key and is ready; a held standby for that key that is not is dropped. */
    private fun standbyFor(source: PlayerSource): Warm? {
        val w = warm?.takeIf { it.key == source.preloadKey } ?: return null
        val ageMs = clock() - w.startedAt
        val usable = w.ready && spare != null
        if (usable) {
            WarmLog.line("swap requested key=${w.key} ageMs=$ageMs readyMs=${w.readyAt - w.startedAt}")
        } else {
            val why = w.error?.let { "failed" } ?: "notReady"
            WarmLog.line("fallback key=${w.key} reason=$why ageMs=$ageMs")
            dropWarm(w, why)
        }
        return w.takeIf { usable }
    }

    /**
     * Moves the app's surface from the front player to the standby and makes the standby the front; the old front is
     * stopped and becomes the standby. The old front lets go of the surface first (Media3 blocks until its renderer
     * has), so the surface never has two producers. Returns how long that took, in ms. Looper only.
     */
    private fun moveSurfaceToStandby(): Long {
        val old = front
        val next = checkNotNull(spare)
        val began = clock()
        old.exo.clearVideoSurface()
        val detachMs = clock() - began
        // With a surface, Media3 moves the running codec onto it (MediaCodec.setOutputSurface): no new decoder.
        surfaceView?.let { next.exo.setVideoSurfaceView(it) }
        old.exo.stop()
        old.exo.clearMediaItems()
        // The old front's preloaded source (if it played one) is off the player now.
        releasePlayedPreload()
        front = next
        spare = old
        warm = null
        handler.removeCallbacks(warmTimeout)
        handler.removeCallbacks(decoderCheck)
        return detachMs
    }

    /** Starts the new front (the standby that was) from [source]'s start, with its picks and its sound. Looper only. */
    @Suppress("LongParameterList") // One hand-over's facts.
    private fun startStandby(
        w: Warm,
        source: PlayerSource,
        generation: Long,
        calledAt: Long,
        parameters: TrackSelectionParameters.Builder,
        detachMs: Long,
    ) {
        if (source.directPlayTracks) {
            picksOnto(parameters, source, exo.currentTracks)
            tracksApplied = true
        }
        exo.trackSelectionParameters = parameters.build()
        val startMs = source.playerStart.inWholeMilliseconds
        val driftMs = exo.currentPosition - startMs
        val seek = abs(driftMs) > WarmSettings.SEEK_TOLERANCE_MS
        if (seek) exo.seekTo(startMs)
        front.holding.set(false)
        exo.volume = 1f
        exo.playWhenReady = true
        // The tracks were known before this play: they are ready at its start, and no load paid for them.
        tracksReported = true
        emit(PlayerEvent.TracksReady(clock(), generation, null))
        swapShown = generation to calledAt
        warmFailures = 0
        WarmLog.line(
            "swap started key=${w.key} slot=${front.name} detachMs=$detachMs driftMs=$driftMs seek=$seek " +
                "surface=${surfaceView != null}",
        )
    }

    /** Ends the standby's play [w] and says why. Looper only. */
    private fun dropWarm(
        w: Warm,
        reason: String,
    ) {
        if (warm !== w) return
        warm = null
        handler.removeCallbacks(warmTimeout)
        handler.removeCallbacks(decoderCheck)
        spare?.let {
            it.exo.stop()
            it.exo.clearMediaItems()
        }
        WarmLog.line("dropped key=${w.key} reason=$reason ageMs=${clock() - w.startedAt}")
    }

    /** The standby failed: drop it, count it, and stop warming after [MAX_WARM_FAILURES] in a row. Looper only. */
    private fun giveUpWarm(
        w: Warm,
        reason: String,
    ) {
        if (warm !== w) return
        w.error = reason
        warmFailures++
        WarmLog.line("fallback key=${w.key} reason=$reason failures=$warmFailures ${WarmLog.memoryFields()}")
        dropWarm(w, "failed")
        if (warmFailures >= MAX_WARM_FAILURES) warmDisabled = true
        if (warmDisabled) {
            WarmLog.line("disabled reason=$reason failures=$warmFailures")
            releaseSpare("disabled")
        }
    }

    /** Frees the standby player (not the preload manager's own player, which is only stopped). Looper only. */
    private fun releaseSpare(reason: String) {
        val slot = spare ?: return
        warm?.let { dropWarm(it, reason) }
        if (slot === slotA) return
        spare = null
        slot.exo.release()
        WarmLog.line("standby released reason=$reason")
    }

    /** One of the standby's events, for its play [w] only (the media id names it). Looper only. */
    private fun standbyEvent(
        eventTime: AnalyticsListener.EventTime,
        update: (Warm) -> Unit,
    ) {
        val w = warm ?: return
        if (mediaIdOf(eventTime)?.let { it != WARM_ID + w.key } == true) return
        update(w)
        if (w.becameReady()) {
            w.readyAt = clock()
            handler.removeCallbacks(warmTimeout)
            handler.removeCallbacks(decoderCheck)
            val slot = spare
            val frames =
                slot?.exo?.videoDecoderCounters?.let {
                    it.ensureUpdated()
                    it.renderedOutputBufferCount
                } ?: -1
            val bufKb = (slot?.allocator?.totalBytesAllocated ?: 0) / KIB
            WarmLog.line(
                "ready key=${w.key} readyMs=${w.readyAt - w.startedAt} decoderAtMs=${w.decoderAt - w.startedAt} " +
                    "decoderInitMs=${w.decoderInitMs} framesDecoded=$frames standbyBufKb=$bufKb " +
                    WarmLog.memoryFields(),
            )
        }
    }

    private fun startPreload(
        key: String,
        source: PlayerSource,
    ) {
        PreloadLog.line("preload running key=$key")
        if (preloads.containsKey(key)) {
            PreloadLog.line("skipped key=$key reason=alreadyPresent")
            return
        }
        val item = mediaItem(source, PRELOAD_ID + key)
        val rank = nextRank++
        preloadStatuses[rank] =
            if (PreloadSettings.BUFFER_MS <= 0L) {
                DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
            } else {
                DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(
                    source.playerStart.inWholeMilliseconds,
                    PreloadSettings.BUFFER_MS,
                )
            }
        preloads[key] = PreloadEntry(item, rank, clock())
        val startMs = source.playerStart.inWholeMilliseconds
        PreloadLog.line("queued key=$key rank=$rank status=$STATUS_NAME startMs=$startMs")
        preloadManager.add(item, rank)
        // add() invalidates already (checked in the 1.11.1 bytecode); once more is harmless.
        preloadManager.invalidate()
    }

    override fun preloadState(key: String): PreloadState? {
        warm?.takeIf { it.key == key }?.let {
            val setup = if (it.ready) it.readyAt - it.startedAt else null
            return PreloadState(clock() - it.startedAt, setup, it.error, warm = true)
        }
        return preloads[key]?.let {
            val done = it.completedAt
            PreloadState(clock() - it.startedAt, if (done == NOT_DONE) null else done - it.startedAt, it.error)
        }
    }

    override fun dropPreload(key: String) =
        onPlayerThread {
            warm?.takeIf { it.key == key }?.let { dropWarm(it, "dropped") }
            preloads.remove(key)?.let {
                preloadStatuses.remove(it.rank)
                preloadManager.remove(it.item)
                preloadManager.invalidate()
                PreloadLog.line("removed key=$key reason=dropped")
            }
        }

    /** The played preload's source is no longer on the player: let the manager release it. Looper only. */
    private fun releasePlayedPreload() {
        playedPreload?.let {
            preloadStatuses.remove(it.rank)
            preloadManager.remove(it.item)
            PreloadLog.line("removed reason=played")
        }
        playedPreload = null
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
            current = null
            active = 0L
            seeking = false
            published = Published.NONE
            memory.stop()
            warm = null
            handler.removeCallbacks(warmTimeout)
            handler.removeCallbacks(decoderCheck)
            // Each player once: the spare may be the preload manager's own player after a warm hand-over.
            listOfNotNull(front, spare, slotA).distinct().forEach { it.exo.release() }
            spare = null
            if (preloads.isNotEmpty()) PreloadLog.line("removed reason=released n=${preloads.size}")
            preloads.clear()
            preloadManager.release()
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
     * (spike #563), and an old front's late events are dropped.
     */
    @Suppress("TooManyFunctions") // One override per Media3 event the player reports.
    private inner class Analytics(
        private val slot: Slot,
    ) : AnalyticsListener {
        private val onFront: Boolean get() = slot === front
        private val isStandby: Boolean get() = slot === spare

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
                // Only a frame on the app's surface is the hand-over's end (not a late one from the hidden output).
                val onScreen = surfaceView?.holder?.surface
                if (onScreen != null && output !== onScreen) return
                swapShown = null
                WarmLog.line("swap shown playToFrameMs=${clock() - shown.second} slot=${slot.name}")
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
            if (isStandby) {
                standbyEvent(eventTime) {
                    if (state == Player.STATE_READY && !it.stateReady) {
                        it.stateReady = true
                        if (it.decoderAt == NOT_DONE) handler.postDelayed(decoderCheck, WarmSettings.DECODER_GRACE_MS)
                    }
                }
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
            if (isStandby) {
                standbyEvent(eventTime) {
                    if (it.decoderAt == NOT_DONE) {
                        it.decoderAt = clock()
                        it.decoderInitMs = initializationDurationMs
                    }
                }
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
            // An error of the standby's own item (a late one of an old front's item is not the standby's).
            if (isStandby) standbyEvent(eventTime) { giveUpWarm(it, "error:${error.errorCodeName}") }
            if (!onFront) return
            // The play ends here: a pending seek will not finish, and says nothing.
            seeking = false
            emit(playerFailure(error, active, clock(), exo.currentPosition, exo.totalBufferedDuration))
        }
    }

    private companion object {
        const val EVENT_BUFFER = 64
        const val NO_POSITION = -1L
        const val NOT_DONE = -1L
        val STATUS_NAME = if (PreloadSettings.BUFFER_MS <= 0L) "tracksSelected" else "rangeLoaded"

        /** The media id prefix of a preloaded item (not a number: its events fall back to the active play). */
        const val PRELOAD_ID = "pre:"

        /** The media id prefix of the standby's item (not a number: after the hand-over it is the active play). */
        const val WARM_ID = "warm:"

        /** After this many standby failures in a row, the player stops warming for its lifetime. */
        const val MAX_WARM_FAILURES = 3
        const val US_PER_MS = 1_000L
        const val KIB = 1_024L

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

/** The media id of the item an event is about; null when it has none yet. */
@UnstableApi
private fun mediaIdOf(eventTime: AnalyticsListener.EventTime): String? {
    val timeline = eventTime.timeline
    if (timeline.isEmpty || eventTime.windowIndex >= timeline.windowCount) return null
    return timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId
}

/** The play an event belongs to: the media id names it; an event with no item yet is [active]'s. */
@UnstableApi
private fun eventGeneration(
    eventTime: AnalyticsListener.EventTime,
    active: Long,
): Long {
    val timeline = eventTime.timeline
    if (timeline.isEmpty || eventTime.windowIndex >= timeline.windowCount) return active
    val id = timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId
    return id.toLongOrNull() ?: active
}
