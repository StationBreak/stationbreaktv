// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

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
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
 * is the ordinary play on the front, as before. [warm] holds the tunables, [warmSwitches] the device-side switches
 * over them (debug builds; [WarmSwitches.None] in release). The standby is released on [release], on a memory trim
 * ([trimMemory]: from [WarmConfig.warmTrimLevel], and at the UI-hidden level and above) and when warming is
 * disabled; [stop] ends only the front's play.
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
@Suppress("TooManyFunctions", "LongParameterList") // The one player: transport and picks (the slots: StandbySlots).
class Media3VideoPlayer internal constructor(
    private val context: Context,
    private val dataSource: DataSource.Factory,
    private val clock: () -> Long,
    private val buffering: PlaybackBuffering,
    private val warm: WarmConfig,
    private val warmSwitches: WarmSwitches,
    // Whether this device may warm: the process's one (DeviceWarmth.of) outside tests, which need a fresh one each.
    device: DeviceWarmth,
    // Automatic volume levelling (#498): null leaves audio alone. Both slots share its one level.
    private val volumeLevelling: VolumeLevelling? = null,
) : VideoPlayer {
    constructor(
        context: Context,
        dataSource: DataSource.Factory,
        clock: () -> Long,
        buffering: PlaybackBuffering = PlaybackBuffering(),
        warm: WarmConfig = WarmConfig(),
        warmSwitches: WarmSwitches = WarmSwitches.None,
        volumeLevelling: VolumeLevelling? = null,
    ) : this(context, dataSource, clock, buffering, warm, warmSwitches, DeviceWarmth.of(context), volumeLevelling)

    init {
        // Before ExoPlayer exists: Media3's own error logs can quote a playlist line with a token.
        silenceMedia3Logging()
        PlaybackLog.configure(context)
    }

    /** The front role's events: on the front ExoPlayer only (StandbySlots moves it at a hand-over). */
    private val frontAnalytics = Analytics()

    /** The front role's picture shape: only the picture on screen sets it, so a hidden standby's size never does. */
    private val frontSize =
        object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) = aspect.onVideoSize(videoSize)
        }

    private val host =
        object : SlotHost {
            override val frontAnalytics: AnalyticsListener get() = this@Media3VideoPlayer.frontAnalytics
            override val frontListener: Player.Listener get() = frontSize

            override fun newSlot(): Slot = buildSlot()

            override fun drawOn(target: ExoPlayer) = this@Media3VideoPlayer.drawOn(target)

            override fun deviceCannot(): String? = device.cannot(warm.warmDeviceDenylist)

            override fun decoderMissing() = device.markDecoderMissing()
        }

    private val handler: Handler

    /** The front and the standby (spec 24). Looper only. */
    private val slots: StandbySlots

    init {
        val first = buildSlot()
        handler = Handler(first.exo.applicationLooper)
        slots = StandbySlots(first, handler, clock, warm, warmSwitches, host)
    }

    private val exo: ExoPlayer get() = slots.front.exo
    private val loadCounter: LoadCounter get() = slots.front.loadCounter
    private val skips: SkippedFrames get() = slots.front.skips

    /** The app's view the front draws on; a warm hand-over moves it. Looper only. */
    private var surfaceView: SurfaceView? = null

    /** A bare surface the front draws on instead, for this module's instrumented tests. Looper only. */
    private var rawSurface: Surface? = null

    // The `SBLIVE mem` line while a play is on the player (diagnostics, OOM of 2026-10-29).
    private val memory =
        MemorySampler(
            allocator = { slots.front.allocator },
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

    private val captionCues = MutableStateFlow<List<Cue>>(emptyList())
    override val cues: StateFlow<List<Cue>> = captionCues.asStateFlow()

    private val nextGeneration = AtomicLong()

    /** The source of the warm handles ([warm]): `w1`, `w2`, ... */
    private val warmHandles = AtomicLong()

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

    /** The front Media3 player, for this module's instrumented tests. The app attaches a surface through [attach]. */
    internal val player: ExoPlayer get() = slots.front.exo

    /** The standby Media3 player, if built, for this module's instrumented tests. */
    internal val standbyPlayer: ExoPlayer? get() = slots.standbyExo

    /** Builds one slot's ExoPlayer, with its own buffer, load counter and holding load control. */
    private fun buildSlot(): Slot {
        val allocator = PlaybackBuffering.mediaAllocator()
        val loadCounter = LoadCounter()
        val holding = AtomicBoolean(false)
        val loadControl =
            HoldingLoadControl(buffering.toLoadControl(allocator), warm.warmHoldBufferMs * US_PER_MS) { holding.get() }
        // The slot's processor is the leading one while this slot is the front: only then does it update the level.
        val built = AtomicReference<Slot?>(null)
        val exo =
            ExoPlayer
                .Builder(context, renderersFactory { slots.front === built.get() })
                .setLoadControl(loadControl)
                .setMediaSourceFactory(CountingMediaSourceFactory(dataSource, loadCounter))
                .build()
        return Slot(exo, allocator, loadCounter, holding).also { built.set(it) }
    }

    /**
     * The default renderers; with [volumeLevelling] the audio sink gets one [LevellingAudioProcessor], tied to the
     * shared [LoudnessLevel] ([leading] says whether this slot is the audible one). Bitstream audio (AC3, E-AC3, DTS,
     * TrueHD) never reaches the processor and the audio capabilities are the defaults.
     */
    private fun renderersFactory(leading: () -> Boolean): DefaultRenderersFactory {
        val levelling = volumeLevelling ?: return DefaultRenderersFactory(context)
        if (PlaybackLog.on) {
            PlaybackLog.line("levelling build storeEnabled=${levelling.enabled()} gainDb=${levelling.level.gainDb}")
        }
        return object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink =
                DefaultAudioSink
                    .Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(
                        arrayOf(LevellingAudioProcessor(levelling.level, leading, levelling.enabled)),
                    ).build()
        }
    }

    override fun attach(surface: SurfaceView?) =
        onPlayerThread {
            surfaceView = surface
            rawSurface = null
            drawOn(exo)
        }

    /**
     * Draws the front on [surface] instead of a view; for this module's instrumented tests only (`internal` is the
     * guard: `androidx.annotation`'s `@VisibleForTesting` is not on this module's classpath, and adding a dependency
     * for it is not worth it).
     */
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
            val taken = source.warmKey?.let { slots.take(it, source) }
            val swap = taken?.handover
            // The new front's shape, if it already knows it; else the last one stays until it reports one.
            if (swap != null) aspect.onVideoSize(exo.videoSize)
            active = generation
            // The last play's caption must not stay up over this one.
            captionCues.value = emptyList()
            // In the same step as `active`, so a reader on another thread never sees the old play under the new number.
            published = Published(generation, source.playerStart.inWholeMilliseconds, false)
            // The play's first event: a hand-over's start may report others at once. `published` is this play's.
            taken?.let { flow.tryEmit(PlayerEvent.WarmDecided(generation, it.outcome)) }
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
            captionCues.value = emptyList()
            seeking = false
            exo.stop()
            exo.clearMediaItems()
            publish()
        }

    /**
     * On the looper (the main thread, where the app calls it) the answer is exact: the same source as the held
     * standby gets its handle back. From another thread the looper cannot be asked, so a new handle is issued and the
     * standby is built again for it.
     */
    override fun warm(source: PlayerSource): String {
        if (Looper.myLooper() == handler.looper) return slots.warmOrReuse(source, ::issueHandle)
        val handle = issueHandle()
        onPlayerThread { slots.warm(handle, source) }
        return handle
    }

    private fun issueHandle(): String = WARM_HANDLE + warmHandles.incrementAndGet()

    override fun dropWarm(key: String) = onPlayerThread { slots.drop(key) }

    override fun trimMemory(level: Int) = onPlayerThread { slots.trim(level) }

    /**
     * Starts the new front (the standby that was) from [source]'s start, with its picks and its sound. Looper only.
     * By design the play's [AvTimings] then lack the ready and video-decoder steps (`av.ready`, `av.videoDecoder`):
     * both happened while it was the standby, before this play began. A warm flip is measured by
     * [PlayerEvent.WarmDecided] and the tune's time to playing instead.
     */
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
        slots.front.holding.set(false)
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

    override suspend fun switchTracks(
        generation: Long,
        switch: TrackSwitch,
    ): Boolean =
        suspendCancellableCoroutine { continuation ->
            onPlayerThread {
                val taken = generation > 0 && active == generation && switchNow(switch)
                continuation.resume(taken)
            }
        }

    /** On the looper: puts [switch] onto the player's selection, or changes nothing and says false. */
    private fun switchNow(switch: TrackSwitch): Boolean {
        val tracks = exo.currentTracks
        val parameters = exo.trackSelectionParameters.buildUpon()
        // An audio pick must find its track and match what the server named, else nothing changes and the caller
        // re-tunes.
        val audioFound = switch.audio?.let { audioOnto(parameters, it, tracks) == true } ?: true
        val subtitleFound =
            when (val subtitles = switch.subtitles) {
                SubtitleSwitch.Keep -> true
                SubtitleSwitch.Off -> true.also { parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true) }
                is SubtitleSwitch.To -> textOnto(parameters, subtitles.pick, tracks)
            }
        if (!audioFound || !subtitleFound) {
            logRefusedSwitch(switch, tracks, audioFound, subtitleFound)
            return false
        }
        exo.trackSelectionParameters = parameters.build()
        return true
    }

    /**
     * The `SBLIVE switchRefused` line: numbers and flags only, so a switch that was meant to be instant and was not can
     * be told apart on a device (the wanted positions against the track groups the player holds, by kind).
     */
    private fun logRefusedSwitch(
        switch: TrackSwitch,
        tracks: Tracks,
        audioFound: Boolean,
        subtitleFound: Boolean,
    ) {
        val audioGroups = tracks.groups.count { it.type == C.TRACK_TYPE_AUDIO }
        val textGroups = tracks.groups.count { it.type == C.TRACK_TYPE_TEXT }
        val subtitles = switch.subtitles
        val textPosition = (subtitles as? SubtitleSwitch.To)?.pick?.position ?: -1
        android.util.Log.i(
            MEMORY_LOG_TAG,
            "switchRefused audioFound=$audioFound audioPosition=${switch.audio?.position ?: -1} " +
                "audioGroups=$audioGroups subtitleFound=$subtitleFound " +
                "textPosition=$textPosition textGroups=$textGroups",
        )
    }

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
            captionCues.value = emptyList()
            seeking = false
            published = Published.NONE
            memory.stop()
            // Both slots: the standby (and any codec it holds) first, then the front.
            slots.release()
            slots.front.exo.release()
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
        source.audioPick?.let { audioOnto(parameters, it, tracks) }
        val subtitlePick = source.subtitlePick
        if (subtitlePick != null) {
            textOnto(parameters, subtitlePick, tracks)
        } else if (source.subtitles.isEmpty()) {
            parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }
    }

    /**
     * Overrides the audio track of [pick]'s position, and records in [lastPickVerified] whether it is the track the
     * server named. Null when the file has no track there (nothing is changed).
     */
    private fun audioOnto(
        parameters: TrackSelectionParameters.Builder,
        pick: TrackPick,
        tracks: Tracks,
    ): Boolean? {
        val group = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.getOrNull(pick.position) ?: return null
        parameters.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        val verified = matches(pick, group.getTrackFormat(0))
        lastPickVerified = verified
        return verified
    }

    /** Shows the text track of [pick]'s position (and turns text on); false when the file has none there. */
    private fun textOnto(
        parameters: TrackSelectionParameters.Builder,
        pick: TrackPick,
        tracks: Tracks,
    ): Boolean {
        val group = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.getOrNull(pick.position) ?: return false
        parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        parameters.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        return true
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
     * The front player's events, which become [PlayerEvent]s. It is on the front ExoPlayer only: a hand-over moves it
     * to the new front ([StandbySlots]), so a hidden standby's events never reach it.
     */
    @Suppress("TooManyFunctions") // One override per Media3 event the player reports.
    private inner class Analytics : AnalyticsListener {
        override fun onTracksChanged(
            eventTime: AnalyticsListener.EventTime,
            tracks: Tracks,
        ) {
            frontTracks(tracks)
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
            val shown = swapShown
            if (shown != null && shown.first == active) {
                // Only a frame on the app's surface ends the hand-over (not a late one from the hidden output).
                val onScreen = surfaceView?.holder?.surface ?: rawSurface
                if (onScreen != null && output !== onScreen) return
                swapShown = null
                WarmLog.line("swap shown playToFrameMs=${clock() - shown.second}")
                // Belt and braces: the shape as of the first frame on screen, should no size event follow the swap.
                aspect.onVideoSize(exo.videoSize)
            }
            emit(PlayerEvent.FirstFrame(clock(), active, skips.sincePlay()))
        }

        override fun onIsPlayingChanged(
            eventTime: AnalyticsListener.EventTime,
            isPlaying: Boolean,
        ) {
            if (!isPlaying) return
            hasPlayed = true
            val timings = av.finish(eventGeneration(eventTime, active))
            emit(PlayerEvent.Playing(clock(), exo.currentPosition.milliseconds, active, timings))
        }

        override fun onPlaybackStateChanged(
            eventTime: AnalyticsListener.EventTime,
            state: Int,
        ) {
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
            // A seek inside the buffer, or to the current position, never leaves READY: no state change would end it.
            if (reason == Player.DISCONTINUITY_REASON_SEEK && exo.playbackState == Player.STATE_READY) finishSeek()
        }

        override fun onCues(
            eventTime: AnalyticsListener.EventTime,
            cueGroup: CueGroup,
        ) {
            // Only the play on the player draws: a late group from a play that has ended must not show.
            if (active != 0L) captionCues.value = cueGroup.cues
            emit(PlayerEvent.Cues(cueGroup.cues.size, active))
        }

        override fun onAudioPositionAdvancing(
            eventTime: AnalyticsListener.EventTime,
            playoutStartSystemTimeMs: Long,
        ) {
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
            av.videoDecoder(eventGeneration(eventTime, active), clock(), initializationDurationMs)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            av.audioDecoder(eventGeneration(eventTime, active), clock(), initializationDurationMs)
        }

        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: AudioSink.AudioTrackConfig,
        ) {
            av.audioTrack(eventGeneration(eventTime, active), clock())
        }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            av.underrun(eventGeneration(eventTime, active))
        }

        override fun onPlayerError(
            eventTime: AnalyticsListener.EventTime,
            error: PlaybackException,
        ) {
            // The play ends here: a pending seek will not finish, and says nothing.
            seeking = false
            PlaybackLog.playerError(error) { "playerError role=front generation=$active posMs=${exo.currentPosition}" }
            emit(playerFailure(error, active, clock(), exo.currentPosition, exo.totalBufferedDuration))
        }
    }

    private companion object {
        const val EVENT_BUFFER = 64
        const val NO_POSITION = -1L
        const val US_PER_MS = 1_000L

        /** The prefix of the warm handles [warm] issues; the player's counter follows it. */
        const val WARM_HANDLE = "w"

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
internal class SkippedFrames(
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

/** The item of [source] under [mediaId]: the media id names the play (or the standby's start). */
internal fun mediaItem(
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

/** The media id of [eventTime]'s item; null when the event has no item. */
@UnstableApi
internal fun mediaIdOf(eventTime: AnalyticsListener.EventTime): String? {
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
