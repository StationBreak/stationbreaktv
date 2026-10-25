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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * The one Media3 player, built once and reused for every play of its host's lifetime. Create it on the thread whose
 * looper should own the player (the main thread); [play], [stop] and [release] may be called from any thread and run
 * on that looper. Media is read through [dataSource], which the app builds from its credentialed OkHttp client, so
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
class Media3VideoPlayer(
    context: Context,
    dataSource: DataSource.Factory,
    private val clock: () -> Long,
) : VideoPlayer {
    init {
        // Before ExoPlayer exists: Media3's own error logs can quote a playlist line with a token.
        silenceMedia3Logging()
    }

    private val loadCounter = LoadCounter()
    private val exo: ExoPlayer =
        ExoPlayer
            .Builder(context)
            .setMediaSourceFactory(CountingMediaSourceFactory(dataSource, loadCounter))
            .build()
    private val handler = Handler(exo.applicationLooper)
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
    private val audio = AudioSampler()

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
        onPlayerThread {
            active = generation
            current = source
            tracksApplied = false
            tracksReported = false
            skips.reset()
            loadCounter.reset(generation)
            hasPlayed = false
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
        }
        return generation
    }

    override fun stop() =
        onPlayerThread {
            current = null
            // The closing audio sample, taken before the stop resets the position.
            if (audio.takeClosingSample()) {
                flow.tryEmit(PlayerEvent.AudioAdvanced(exo.currentPosition.milliseconds, active))
            }
            exo.stop()
            exo.clearMediaItems()
        }

    override fun release() =
        onPlayerThread {
            current = null
            exo.release()
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

    private inner class Analytics : AnalyticsListener {
        override fun onTracksChanged(
            eventTime: AnalyticsListener.EventTime,
            tracks: Tracks,
        ) {
            if (!tracksReported && tracks.groups.isNotEmpty()) {
                tracksReported = true
                flow.tryEmit(PlayerEvent.TracksReady(clock(), active, loadCounter.close(containerMime(tracks))))
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
            flow.tryEmit(PlayerEvent.FirstFrame(clock(), active, skips.sincePlay()))
        }

        override fun onIsPlayingChanged(
            eventTime: AnalyticsListener.EventTime,
            isPlaying: Boolean,
        ) {
            if (!isPlaying) return
            hasPlayed = true
            flow.tryEmit(PlayerEvent.Playing(clock(), exo.currentPosition.milliseconds, active))
        }

        override fun onPlaybackStateChanged(
            eventTime: AnalyticsListener.EventTime,
            state: Int,
        ) {
            // A rebuffer: buffering again after playback had begun.
            if (state == Player.STATE_BUFFERING && hasPlayed) flow.tryEmit(PlayerEvent.Stalled(active))
            if (state == Player.STATE_ENDED) flow.tryEmit(PlayerEvent.Ended(active))
        }

        override fun onCues(
            eventTime: AnalyticsListener.EventTime,
            cueGroup: CueGroup,
        ) {
            flow.tryEmit(PlayerEvent.Cues(cueGroup.cues.size, active))
        }

        override fun onAudioPositionAdvancing(
            eventTime: AnalyticsListener.EventTime,
            playoutStartSystemTimeMs: Long,
        ) {
            audio.advancing()
            flow.tryEmit(PlayerEvent.AudioAdvanced(exo.currentPosition.milliseconds, active))
        }

        override fun onPlayerError(
            eventTime: AnalyticsListener.EventTime,
            error: PlaybackException,
        ) {
            flow.tryEmit(playerFailure(error, active))
        }
    }

    private companion object {
        const val EVENT_BUFFER = 64

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
