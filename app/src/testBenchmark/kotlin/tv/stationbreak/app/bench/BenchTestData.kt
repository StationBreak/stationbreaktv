// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber", "LongParameterList") // Scripted times and sample values; detekt skips only src/test.

package tv.stationbreak.app.bench

import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.StreamKind
import tv.stationbreak.core.model.SubtitleDelivery
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.playback.PlayerSource
import tv.stationbreak.core.playback.ResolvedPlayback
import tv.stationbreak.core.playback.Route
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** A resolved playback whose title, ID and URL are the kind of thing that must never reach a result line. */
internal object BenchTestData {
    val session =
        Session(
            UserId("u1"),
            AccessToken("tok"),
            ServerInfo("Home", ServerId("abc"), ServerVersion(12, 2, 0)),
            ServerAddress(Scheme.Https, "media.example.com", 443),
            null,
            false,
        )

    @Suppress("LongParameterList") // Test data: every parameter is one optional fact.
    private fun stream(
        index: Int,
        kind: StreamKind,
        codec: String,
        channels: Int? = null,
        width: Int? = null,
        height: Int? = null,
        delivery: SubtitleDelivery? = null,
        bitDepth: Int? = null,
    ) = MediaStreamInfo(
        index = index,
        kind = kind,
        codec = codec,
        language = "eng",
        channels = channels,
        width = width,
        height = height,
        isExternal = false,
        isDefault = false,
        isForced = false,
        delivery = delivery,
        positionInKind = 0,
        bitDepth = bitDepth,
    )

    val video = stream(0, StreamKind.VIDEO, Codecs.HEVC, width = 1920, height = 1080, bitDepth = 10)
    val audio = stream(1, StreamKind.AUDIO, "dts", channels = 6)

    fun textSubtitle() = stream(2, StreamKind.SUBTITLE, "subrip", delivery = SubtitleDelivery.IN_STREAM)

    fun imageSubtitle() = stream(3, StreamKind.SUBTITLE, "pgssub", delivery = SubtitleDelivery.BURNED_IN)

    fun media(
        container: String? = "mkv",
        streams: List<MediaStreamInfo> = listOf(video, audio, textSubtitle(), imageSubtitle()),
        directPlay: Boolean = true,
        defaultSubtitle: Int? = null,
    ) = MediaSource(
        id = MediaSourceId("abc123"),
        name = "Secret Movie (1999)",
        container = container,
        runtime = 90.minutes,
        supportsDirectPlay = directPlay,
        supportsDirectStream = true,
        supportsTranscoding = true,
        streams = streams,
        defaultAudioIndex = 1,
        defaultSubtitleIndex = defaultSubtitle,
    )

    fun resolved(
        route: Route,
        subtitle: MediaStreamInfo? = null,
    ) = ResolvedPlayback(
        source =
            PlayerSource(
                url = "https://media.example.com/Videos/abc123/master.m3u8",
                isHls = route != Route.DIRECT_PLAY,
                serverOffset = Duration.ZERO,
                playerStart = Duration.ZERO,
                subtitles = emptyList(),
                audioIndex = 1,
                subtitleIndex = subtitle?.index,
                directPlayTracks = route == Route.DIRECT_PLAY,
            ),
        route = route,
        playSession = PlaySessionId("ps1"),
        media = media(),
        chosenAudio = audio,
        chosenSubtitle = subtitle,
        requested = Duration.ZERO,
    )
}
