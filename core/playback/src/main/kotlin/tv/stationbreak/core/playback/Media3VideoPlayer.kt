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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
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
    context: Context,
    dataSource: DataSource.Factory,
    private val clock: () -> Long,
    buffering: PlaybackBuffering = PlaybackBuffering(),
) : VideoPlayer {
    init {
        // Before ExoPlayer exists: Media3's own error logs can quote a playlist line with a token.
        silenceMedia3Logging()
    }

    private val loadCounter = LoadCounter()
    private val allocator = PlaybackBuffering.mediaAllocator()
    private val loadControl = buffering.toLoadControl(allocator)
    private val mediaSourceFactory = CountingMediaSourceFactory(dataSource, loadCounter)

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
            ).setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
    private val exo: ExoPlayer =
        preloadBuilder.buildExoPlayer(
            ExoPlayer
                .Builder(context)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(mediaSourceFactory),
        )
    private val preloadManager = preloadBuilder.build()
    private val handler = Handler(exo.applicationLooper)

    // The `SBLIVE mem` line while a play is on the player (diagnostics, OOM of 2026-10-29).
    private val memory =
        MemorySampler(
            allocator = allocator,
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

    /** The generation of the play now on the player; set on its looper, so events and plays agree. */
    @Volatile private var active = 0L
    private var current: PlayerSource? = null
    private var tracksApplied = false
    private var tracksReported = false
    private val skips = SkippedFrames(exo)

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
        exo.addAnalyticsListener(Analytics())
    }

    /** The Media3 player, for this module's instrumented tests. The app attaches a surface through [attach]. */
    internal val player: Player get() = exo

    override fun attach(surface: SurfaceView?) =
        onPlayerThread {
            if (surface == null) exo.clearVideoSurface() else exo.setVideoSurfaceView(surface)
        }

    override fun play(source: PlayerSource): Long {
        val generation = nextGeneration.incrementAndGet()
        val calledAt = clock()
        onPlayerThread {
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
            exo.trackSelectionParameters =
                exo.trackSelectionParameters
                    .buildUpon()
                    .clearOverrides()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .build()
            playId = generation.toString()
            val preloaded = source.preloadKey?.let { preloads.remove(it) }
            val usable = preloaded?.takeIf { it.error == null }
            val prepared = usable?.let { preloadManager.getMediaSource(it.item) }
            if (source.preloadKey != null) {
                PreloadLog.line("handover key=${source.preloadKey} used=${prepared != null} entry=${preloaded != null}")
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
    ) = onPlayerThread {
        if (preloads.containsKey(key)) {
            PreloadLog.line("skipped key=$key reason=alreadyPresent")
            return@onPlayerThread
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

    override fun preloadState(key: String): PreloadState? =
        preloads[key]?.let {
            val done = it.completedAt
            PreloadState(clock() - it.startedAt, if (done == NOT_DONE) null else done - it.startedAt, it.error)
        }

    override fun dropPreload(key: String) =
        onPlayerThread {
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
            exo.release()
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
        exo.trackSelectionParameters = parameters.build()
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

    @Suppress("TooManyFunctions") // One override per Media3 event the player reports.
    private inner class Analytics : AnalyticsListener {
        override fun onTracksChanged(
            eventTime: AnalyticsListener.EventTime,
            tracks: Tracks,
        ) {
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
        ) = av.videoDecoder(eventGeneration(eventTime, active), clock(), initializationDurationMs)

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) = av.audioDecoder(eventGeneration(eventTime, active), clock(), initializationDurationMs)

        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: AudioSink.AudioTrackConfig,
        ) = av.audioTrack(eventGeneration(eventTime, active), clock())

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) = av.underrun(eventGeneration(eventTime, active))

        override fun onPlayerError(
            eventTime: AnalyticsListener.EventTime,
            error: PlaybackException,
        ) {
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
