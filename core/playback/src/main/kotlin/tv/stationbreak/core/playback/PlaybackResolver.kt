// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.tracks.SubtitleCarry
import tv.stationbreak.core.domain.tracks.TrackCarry
import tv.stationbreak.core.domain.tracks.TrackPreferences
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
@Suppress("TooManyFunctions") // The resolve calls and the per-source decisions; the pure helpers are below.
class PlaybackResolver(
    private val server: MediaServer,
    private val capabilities: () -> DeviceCapabilities,
) {
    /**
     * Resolves [item]. A [choice] names the version to play ([TrackChoice.source]; only that one is resolved when the
     * answer has it, so a fallback transcodes the version that just failed, not the first, which may be the heaviest)
     * and, optionally, the tracks within it. If the chosen source is not in the answer its tracks are dropped and the
     * item is resolved again as if nothing was chosen: an override's indices are one source's, and must never be
     * applied to another version.
     *
     * [preferences] are the carried language preferences (design doc B2.6). They apply only where the viewer made no
     * choice of tracks, and only after resolving, because the indices are in the answer: the tracks they pick are asked
     * for in a second `PlaybackInfo` call when they differ from what the server's defaults gave (the route and the
     * delivery depend on the tracks), and not at all when they agree. A failed second call leaves the first answer.
     */
    @Suppress("LongParameterList") // One call's context.
    suspend fun resolve(
        session: Session,
        item: ItemId,
        start: Duration,
        choice: TrackChoice?,
        forceTranscode: Boolean = false,
        preferences: TrackPreferences? = null,
    ): ServerResult<ResolvedPlayback> {
        val limits = capabilities()
        val first = resolveOnce(session, item, start, choice, forceTranscode, limits)
        val played = (first as? ServerResult.Success)?.value ?: return first
        return when {
            choice?.tracks != null && played.sources.none { it.id == choice.source } -> {
                resolve(session, item, start, null, forceTranscode, preferences)
            }

            choice?.tracks != null || preferences == null -> {
                first
            }

            else -> {
                carried(session, item, start, played, forceTranscode, limits, preferences) ?: first
            }
        }
    }

    @Suppress("LongParameterList") // One call's context, as in resolve and resolveOnce.
    private suspend fun carried(
        session: Session,
        item: ItemId,
        start: Duration,
        played: ResolvedPlayback,
        forceTranscode: Boolean,
        limits: DeviceCapabilities,
        preferences: TrackPreferences,
    ): ServerResult<ResolvedPlayback>? {
        val tracks = TrackCarry.tracksFor(preferences, played.media)
        val override = TrackOverride(tracks.audioIndex, tracks.subtitles.toChoice())
        if (tracks.isServerDefault || played.alreadyPlays(override)) return null
        val second = resolveOnce(session, item, start, TrackChoice(played.media.id, override), forceTranscode, limits)
        return second.takeIf { it is ServerResult.Success }
    }

    @Suppress("LongParameterList") // One call's context.
    private suspend fun resolveOnce(
        session: Session,
        item: ItemId,
        start: Duration,
        choice: TrackChoice?,
        forceTranscode: Boolean,
        limits: DeviceCapabilities,
    ): ServerResult<ResolvedPlayback> {
        val override = choice?.tracks
        val subtitleIndex =
            when (val subtitle = override?.subtitle) {
                null, SubtitleChoice.Default -> null
                SubtitleChoice.Off -> PlaybackRequest.SUBTITLES_OFF
                is SubtitleChoice.Track -> subtitle.index
            }
        val request = PlaybackRequest(limits, start, override?.audioIndex, subtitleIndex, forceTranscode)
        val inputs = ResolveInputs(limits, start, override, forceTranscode)
        return when (val answer = server.playbackInfo(session, item, request)) {
            is ServerResult.Failure -> answer
            is ServerResult.Success -> resolveAnswer(answer.value, inputs, choice?.source)
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
        // Every version the server offered, not only the preferred one: a version picker needs them all.
        return resolved?.let { ServerResult.Success(it.copy(sources = info.sources)) }
            ?: ServerResult.Failure(MediaServerError.NotFound())
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
        val subtitleChoice = subtitleIndexFor(media, override)
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
        return ResolvedPlayback(source, route, info.playSessionId, media, audio, subtitle, start, limits = limits)
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

/** The subtitle stream [override] chooses on [media]: its own, none for off, else the source's default. */
private fun subtitleIndexFor(
    media: MediaSource,
    override: TrackOverride?,
): Int? =
    when (val wanted = override?.subtitle) {
        null, SubtitleChoice.Default -> media.defaultSubtitleIndex
        SubtitleChoice.Off -> null
        is SubtitleChoice.Track -> wanted.index
    }

private fun SubtitleCarry.toChoice(): SubtitleChoice =
    when (this) {
        SubtitleCarry.ServerDefault -> SubtitleChoice.Default
        SubtitleCarry.Off -> SubtitleChoice.Off
        is SubtitleCarry.Track -> SubtitleChoice.Track(index)
    }

/** True when the server's defaults already gave the tracks [override] asks for. */
private fun ResolvedPlayback.alreadyPlays(override: TrackOverride): Boolean {
    val audioSame = override.audioIndex == null || override.audioIndex == chosenAudio?.index
    val subtitleSame =
        when (val wanted = override.subtitle) {
            SubtitleChoice.Default -> true
            SubtitleChoice.Off -> chosenSubtitle == null
            is SubtitleChoice.Track -> chosenSubtitle?.index == wanted.index
        }
    return audioSame && subtitleSame
}
