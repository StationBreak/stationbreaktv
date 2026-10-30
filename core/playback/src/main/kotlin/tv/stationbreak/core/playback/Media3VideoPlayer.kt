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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
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
    private val exo: ExoPlayer =
        ExoPlayer
            .Builder(context)
            .setLoadControl(buffering.toLoadControl(allocator))
            .setMediaSourceFactory(CountingMediaSourceFactory(dataSource, loadCounter))
            .build()
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
    private val flow =
        MutableSharedFlow<PlayerEvent>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val events: SharedFlow<PlayerEvent> = flow

    private val nextGeneration = AtomicLong()

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

    /** What the looper last saw, for [position] and [seekable] off the looper; [NO_POSITION] for none. */
    @Volatile private var publishedPositionMs = NO_POSITION

    @Volatile private var publishedSeekable = false
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
            exo.setMediaItem(mediaItem(source, playId), source.playerStart.inWholeMilliseconds)
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
            publish()
        }

    override fun pause(generation: Long) =
        onPlayerThread {
            if (active != generation) return@onPlayerThread
            exo.playWhenReady = false
            emit(PlayerEvent.Paused(generation, clock(), exo.currentPosition))
        }

    override fun resume(generation: Long) =
        onPlayerThread {
            if (active != generation) return@onPlayerThread
            emit(PlayerEvent.Resumed(generation, clock()))
            exo.playWhenReady = true
        }

    override fun seekTo(
        generation: Long,
        position: Duration,
    ) = onPlayerThread {
        if (active != generation) return@onPlayerThread
        val to = position.inWholeMilliseconds
        // Set first: Media3 may report the finish from inside the seek call.
        seeking = true
        emit(PlayerEvent.Seeking(generation, clock(), exo.currentPosition, to))
        exo.seekTo(to)
        publish()
    }

    override fun position(generation: Long): Duration? {
        if (active != generation) return null
        if (Looper.myLooper() == handler.looper) publish()
        return publishedPositionMs.takeIf { it != NO_POSITION }?.milliseconds
    }

    override fun seekable(generation: Long): Boolean {
        if (active != generation) return false
        if (Looper.myLooper() == handler.looper) publish()
        return publishedSeekable
    }

    override fun release() =
        onPlayerThread {
            current = null
            active = 0L
            seeking = false
            publishedPositionMs = NO_POSITION
            publishedSeekable = false
            memory.stop()
            exo.release()
        }

    /** Sends [event] and refreshes what [position] and [seekable] answer from other threads. Looper only. */
    private fun emit(event: PlayerEvent) {
        flow.tryEmit(event)
        publish()
    }

    private fun publish() {
        if (active == 0L) {
            publishedPositionMs = NO_POSITION
            publishedSeekable = false
        } else {
            publishedPositionMs = exo.currentPosition
            publishedSeekable = exo.isCurrentMediaItemSeekable
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
            if (state == Player.STATE_READY) finishSeek()
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
            emit(playerFailure(error, active, clock(), exo.currentPosition, exo.totalBufferedDuration))
        }
    }

    private companion object {
        const val EVENT_BUFFER = 64
        const val NO_POSITION = -1L

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
