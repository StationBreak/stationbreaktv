// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.DeviceCapabilities
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.PlaybackInfo
import tv.stationbreak.core.model.PlaybackRequest
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.StreamKind
import tv.stationbreak.core.model.SubtitleDelivery
import kotlin.time.Duration

/**
 * Decides how to play an item from the server's answer and the device's [capabilities]. It asks the server once,
 * chooses one of its sources (see [choose]), and picks the cheapest route the client agrees with. The client overrules
 * the server's `SupportsDirectPlay` where the chosen audio could not be decoded here or the subtitle is burned in
 * (ADR-0016, 8). A [prefer]red source that is in the answer is the only one resolved.
 */
class PlaybackResolver(
    private val server: MediaServer,
    private val capabilities: () -> DeviceCapabilities,
) {
    /**
     * Resolves [item]. When [prefer] names a source in the answer, only that one is resolved (a fallback transcodes the
     * version that just failed, not the first, which may be the heaviest); otherwise the choice is [choose]'s. A caller
     * that passes a [TrackOverride] should pass [prefer] (the current `media.id`), because its indices are one
     * source's stream indices and must keep meaning the same tracks.
     */
    @Suppress("LongParameterList") // One call's context.
    suspend fun resolve(
        session: Session,
        item: ItemId,
        start: Duration,
        override: TrackOverride?,
        forceTranscode: Boolean = false,
        prefer: MediaSourceId? = null,
    ): ServerResult<ResolvedPlayback> {
        val limits = capabilities()
        val request = PlaybackRequest(limits, start, override?.audioIndex, override?.subtitleIndex, forceTranscode)
        val inputs = ResolveInputs(limits, start, override, forceTranscode)
        return when (val answer = server.playbackInfo(session, item, request)) {
            is ServerResult.Failure -> answer
            is ServerResult.Success -> resolveAnswer(answer.value, inputs, prefer)
        }
    }

    private fun resolveAnswer(
        info: PlaybackInfo,
        inputs: ResolveInputs,
        prefer: MediaSourceId?,
    ): ServerResult<ResolvedPlayback> {
        val preferred = if (prefer != null) info.sources.filter { it.id == prefer } else emptyList()
        val sources = preferred.ifEmpty { info.sources }
        val candidates = sources.map { resolveSource(info, it, inputs) }
        val resolved = choose(candidates, inputs.limits)
        return resolved?.let { ServerResult.Success(it) } ?: ServerResult.Failure(MediaServerError.NotFound())
    }

    /**
     * An item can have several versions (issue #496). Each is resolved on its own, so the choice sees the route the
     * client would really take. In order: a version this device can direct-play, the biggest picture among those that
     * fit its decoder, else Jellyfin's order (the first). The URL and the report both carry the chosen version's id,
     * because they are built from the one [ResolvedPlayback.media].
     */
    private fun choose(
        candidates: List<ResolvedPlayback>,
        limits: DeviceCapabilities,
    ): ResolvedPlayback? {
        val direct = candidates.filter { it.route == Route.DIRECT_PLAY }
        val fitting = direct.filter { it.media.fits(limits) }
        // maxByOrNull keeps the first of equal pictures, so a tie is Jellyfin's order.
        return fitting.maxByOrNull { it.media.pixels() } ?: direct.firstOrNull() ?: candidates.firstOrNull()
    }

    private fun MediaSource.video(): MediaStreamInfo? = streams.firstOrNull { it.kind == StreamKind.VIDEO }

    private fun MediaSource.pixels(): Long {
        val video = video() ?: return 0L
        return (video.width ?: 0).toLong() * (video.height ?: 0)
    }

    /** True unless the video is bigger than its codec's decoder allows; an unreported size is let be. */
    private fun MediaSource.fits(limits: DeviceCapabilities): Boolean {
        val video = video()
        val support = limits.videoCodecs.firstOrNull { it.codec == Codecs.normalize(video?.codec) }
        return video == null || support == null ||
            ((video.width ?: 0) <= support.maxWidth && (video.height ?: 0) <= support.maxHeight)
    }

    private fun resolveSource(
        info: PlaybackInfo,
        media: MediaSource,
        inputs: ResolveInputs,
    ): ResolvedPlayback {
        val (limits, start, override) = inputs
        val forceTranscode = inputs.forceTranscode
        // An override replaces the server's choice; its null subtitle means "off", its null audio "the server's".
        // Only a real audio track of this source is ever chosen: an index it lacks, or one that is not audio, falls
        // back to the server's default, then the first audio track. What is chosen is what the URL and the check see.
        val audio =
            override?.audioIndex?.let { media.stream(StreamKind.AUDIO, it) }
                ?: media.stream(StreamKind.AUDIO, media.defaultAudioIndex)
                ?: media.streams.firstOrNull { it.kind == StreamKind.AUDIO }
        val audioIndex = audio?.index
        val subtitleChoice = if (override != null) override.subtitleIndex else media.defaultSubtitleIndex
        val chosen = media.stream(StreamKind.SUBTITLE, subtitleChoice)
        val burnedIn = chosen?.delivery == SubtitleDelivery.BURNED_IN
        val route = route(media, limits, audio, burnedIn, forceTranscode)
        val hls = route != Route.DIRECT_PLAY
        // On an HLS route the playlist carries no text subtitle, so an embedded one is fetched as WebVTT instead: the
        // server extracts it (ADR-0016, update 2026-10-22). Direct play shows it from the file itself.
        val wantedSubtitle =
            if (hls && chosen != null && chosen.inStreamText()) {
                chosen.copy(delivery = SubtitleDelivery.SEPARATE)
            } else {
                chosen
            }
        val separate =
            wantedSubtitle
                ?.takeIf { it.delivery == SubtitleDelivery.SEPARATE }
                ?.let { stream ->
                    info.streams.subtitle(media.id, stream)?.let { SeparateSubtitle(it, stream.language) }
                }
        // A separate subtitle the server gave no URL for cannot be shown, so it is not reported as chosen either.
        val subtitle =
            wantedSubtitle?.takeUnless { it.delivery == SubtitleDelivery.SEPARATE && separate == null }
        val subtitleIndex = subtitle?.index
        val url =
            if (hls) {
                val burnIn = if (burnedIn) subtitleIndex else null
                info.streams.hls(media, audioIndex, burnIn, limits, forceTranscode)
            } else {
                info.streams.original(media.id)
            }
        val source =
            PlayerSource(
                url = url,
                isHls = hls,
                // HLS starts like Jellyfin's own clients: the playlist covers the whole runtime from 0 and the server
                // rejects StartTimeTicks on segments (12.2), so the player seeks, as in direct play.
                playerStart = start,
                subtitles = listOfNotNull(separate),
                audioIndex = audioIndex,
                subtitleIndex = subtitleIndex,
                directPlayTracks = route == Route.DIRECT_PLAY,
                audioPick = audio?.pick(),
                subtitlePick = subtitle?.takeIf { it.delivery == SubtitleDelivery.IN_STREAM }?.pick(),
            )
        return ResolvedPlayback(source, route, info.playSessionId, media, audio, subtitle, start)
    }

    private fun route(
        media: MediaSource,
        limits: DeviceCapabilities,
        audio: MediaStreamInfo?,
        burnedIn: Boolean,
        forceTranscode: Boolean,
    ): Route =
        when {
            forceTranscode -> Route.TRANSCODE
            burnedIn -> Route.TRANSCODE
            media.supportsDirectPlay && audioPlays(limits, audio) -> Route.DIRECT_PLAY
            media.supportsDirectStream -> Route.DIRECT_STREAM
            else -> Route.TRANSCODE
        }

    private fun MediaStreamInfo.inStreamText() = delivery == SubtitleDelivery.IN_STREAM && Codecs.isTextSubtitle(codec)

    private fun MediaStreamInfo.pick(): TrackPick? = positionInKind?.let { TrackPick(it, language, codec) }

    private fun MediaSource.stream(
        kind: StreamKind,
        index: Int?,
    ): MediaStreamInfo? = index?.let { wanted -> streams.firstOrNull { it.kind == kind && it.index == wanted } }
}

/**
 * Can this device play [audio] (null: no audio track, nothing to refuse) as it is? The codec must be one it decodes or
 * passes through to the HDMI sink. The channel limit is the decoder's, so it applies to decoded audio only: a
 * passthrough codec leaves the app as it is and the sink decides (ADR-0016 is silent; this is the reading chosen).
 * The test oracle ([ExpectedRoute]) has its own rule; a table test holds the two to the same answers.
 */
internal fun audioPlays(
    capabilities: DeviceCapabilities,
    audio: MediaStreamInfo?,
): Boolean {
    if (audio == null) return true
    val codec = Codecs.normalize(audio.codec)
    return when {
        codec == null -> false
        codec in capabilities.audioPassthrough -> true
        codec in capabilities.audioDecoders -> (audio.channels ?: 0) <= capabilities.maxAudioChannels
        else -> false
    }
}

/** The facts of one resolve call, passed on unchanged to every source's resolution. */
private data class ResolveInputs(
    val limits: DeviceCapabilities,
    val start: Duration,
    val override: TrackOverride?,
    val forceTranscode: Boolean,
)
