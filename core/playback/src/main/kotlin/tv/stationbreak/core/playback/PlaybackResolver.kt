// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.DeviceCapabilities
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.PlaybackInfo
import tv.stationbreak.core.model.PlaybackRequest
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.StreamKind
import tv.stationbreak.core.model.SubtitleDelivery
import kotlin.time.Duration

/**
 * Decides how to play an item from the server's answer and the device's [capabilities]. It asks the server once,
 * takes its first source, and picks the cheapest route the client agrees with. The client overrules the server's
 * `SupportsDirectPlay` where the chosen audio could not be decoded here or the subtitle is burned in (ADR-0016, 8).
 */
class PlaybackResolver(
    private val server: MediaServer,
    private val capabilities: () -> DeviceCapabilities,
) {
    suspend fun resolve(
        session: Session,
        item: ItemId,
        start: Duration,
        override: TrackOverride?,
        forceTranscode: Boolean = false,
    ): ServerResult<ResolvedPlayback> {
        val limits = capabilities()
        val request = PlaybackRequest(limits, start, override?.audioIndex, override?.subtitleIndex, forceTranscode)
        return when (val answer = server.playbackInfo(session, item, request)) {
            is ServerResult.Failure -> answer
            is ServerResult.Success -> resolveAnswer(answer.value, limits, start, override, forceTranscode)
        }
    }

    private fun resolveAnswer(
        info: PlaybackInfo,
        limits: DeviceCapabilities,
        start: Duration,
        override: TrackOverride?,
        forceTranscode: Boolean,
    ): ServerResult<ResolvedPlayback> {
        val media = info.sources.firstOrNull() ?: return ServerResult.Failure(MediaServerError.NotFound())
        // An override replaces the server's choice; its null subtitle means "off", its null audio "the server's".
        // Only a real audio track of this source is ever chosen: an index it lacks, or one that is not audio, falls
        // back to the server's default, then the first audio track. What is chosen is what the URL and the check see.
        val audio =
            override?.audioIndex?.let { media.stream(StreamKind.AUDIO, it) }
                ?: media.stream(StreamKind.AUDIO, media.defaultAudioIndex)
                ?: media.streams.firstOrNull { it.kind == StreamKind.AUDIO }
        val audioIndex = audio?.index
        val subtitleChoice = if (override != null) override.subtitleIndex else media.defaultSubtitleIndex
        val wantedSubtitle = media.stream(StreamKind.SUBTITLE, subtitleChoice)
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
        val burnedIn = subtitle?.delivery == SubtitleDelivery.BURNED_IN
        val route = route(media, limits, audio, burnedIn, forceTranscode)
        val hls = route != Route.DIRECT_PLAY
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
        val resolved = ResolvedPlayback(source, route, info.playSessionId, media, audio, subtitle, start)
        return ServerResult.Success(resolved)
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
            media.supportsDirectPlay && !burnedIn && audioPlays(limits, audio) -> Route.DIRECT_PLAY
            media.supportsDirectStream -> Route.DIRECT_STREAM
            else -> Route.TRANSCODE
        }

    private fun MediaStreamInfo.pick(): TrackPick? = positionInKind?.let { TrackPick(it, language, codec) }

    private fun MediaSource.stream(
        kind: StreamKind,
        index: Int?,
    ): MediaStreamInfo? = index?.let { wanted -> streams.firstOrNull { it.kind == kind && it.index == wanted } }
}
