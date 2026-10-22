// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.DeviceCapabilities
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.StreamKind
import tv.stationbreak.core.model.StreamUrls
import tv.stationbreak.core.model.SubtitleDelivery
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.model.VideoCodecSupport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** What one [StreamUrls.hls] call was given. */
data class HlsCall(
    val audioIndex: Int?,
    val burnIn: Int?,
    val forceTranscode: Boolean,
)

class RecordingUrls : StreamUrls {
    val hlsCalls = mutableListOf<HlsCall>()
    var hasSubtitleUrls = true

    override fun original(source: MediaSourceId): String = "https://media.example.com/original/${source.value}"

    override fun hls(
        source: MediaSource,
        audioIndex: Int?,
        burnIn: Int?,
        limits: DeviceCapabilities,
        forceTranscode: Boolean,
    ): String {
        hlsCalls += HlsCall(audioIndex, burnIn, forceTranscode)
        return "https://media.example.com/hls/${source.id.value}"
    }

    override fun subtitle(
        source: MediaSourceId,
        stream: MediaStreamInfo,
    ): String? = if (hasSubtitleUrls) "https://media.example.com/sub/${source.value}/${stream.index}" else null
}

/** `kotlin.test.assertIs` for a module that tests with plain JUnit. */
inline fun <reified T> assertIs(value: Any?): T {
    org.junit.Assert.assertTrue("Expected ${T::class.simpleName} but was $value", value is T)
    return value as T
}

@Suppress("LongParameterList") // Builders for test data: every parameter is one optional fact.
object PlaybackTestData {
    val address = ServerAddress(Scheme.Https, "media.example.com", 443)
    val session =
        Session(
            UserId("u1"),
            AccessToken("tok"),
            ServerInfo("Home", ServerId("abc"), ServerVersion(12, 2, 0)),
            address,
            null,
            false,
        )

    private val h264 =
        VideoCodecSupport(
            Codecs.H264,
            setOf(Codecs.BASELINE, Codecs.MAIN, Codecs.HIGH),
            41,
            1920,
            1080,
            8,
            emptySet(),
        )

    /** Roughly the Insignia: h264 up to 4.1 at 1080p, aac/mp3/ac3 decoders, no passthrough, stereo. */
    fun insigniaLike(
        videoCodecs: List<VideoCodecSupport> = listOf(h264),
        audioDecoders: Set<String> = setOf(Codecs.AAC, Codecs.MP3, Codecs.AC3),
        passthrough: Set<String> = emptySet(),
        maxChannels: Int = 2,
    ) = DeviceCapabilities(
        containers = setOf(Codecs.MP4, Codecs.M4V, Codecs.MKV, Codecs.WEBM, Codecs.TS),
        videoCodecs = videoCodecs,
        audioDecoders = audioDecoders,
        audioPassthrough = passthrough,
        maxAudioChannels = maxChannels,
    )

    fun video(
        codec: String = Codecs.H264,
        profile: String? = Codecs.HIGH,
        level: Int? = 41,
        width: Int = 1920,
        height: Int = 1080,
        bitDepth: Int? = 8,
    ) = MediaStreamInfo(
        index = 0,
        kind = StreamKind.VIDEO,
        codec = codec,
        language = null,
        channels = null,
        width = width,
        height = height,
        isExternal = false,
        isDefault = true,
        isForced = false,
        delivery = null,
        positionInKind = 0,
        videoProfile = profile,
        videoLevel = level,
        bitDepth = bitDepth,
    )

    fun audio(
        index: Int = 1,
        codec: String = Codecs.AAC,
        channels: Int = 2,
    ) = MediaStreamInfo(
        index = index,
        kind = StreamKind.AUDIO,
        codec = codec,
        language = "eng",
        channels = channels,
        width = null,
        height = null,
        isExternal = false,
        isDefault = true,
        isForced = false,
        delivery = null,
        positionInKind = 0,
    )

    fun subtitle(
        index: Int,
        codec: String = "subrip",
        delivery: SubtitleDelivery = SubtitleDelivery.IN_STREAM,
        language: String? = "eng",
    ) = MediaStreamInfo(
        index = index,
        kind = StreamKind.SUBTITLE,
        codec = codec,
        language = language,
        channels = null,
        width = null,
        height = null,
        isExternal = false,
        isDefault = false,
        isForced = false,
        delivery = delivery,
        positionInKind = 0,
    )

    fun source(
        directPlay: Boolean = true,
        directStream: Boolean = true,
        container: String? = Codecs.MP4,
        streams: List<MediaStreamInfo> = listOf(video(), audio()),
        defaultAudio: Int? = 1,
        defaultSubtitle: Int? = null,
    ) = MediaSource(
        id = MediaSourceId("src1"),
        name = null,
        container = container,
        runtime = 90.minutes,
        supportsDirectPlay = directPlay,
        supportsDirectStream = directStream,
        supportsTranscoding = true,
        streams = streams,
        defaultAudioIndex = defaultAudio,
        defaultSubtitleIndex = defaultSubtitle,
    )
}
