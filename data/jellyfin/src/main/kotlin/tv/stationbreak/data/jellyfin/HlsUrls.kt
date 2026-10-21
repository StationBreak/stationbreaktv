// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.PlaybackRequest
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.StreamKind
import tv.stationbreak.core.model.StreamUrls
import tv.stationbreak.core.model.SubtitleDelivery

/**
 * Stream URLs made from the base address, IDs and the device's own limits, and nothing else: never the server's
 * `TranscodingUrl`, `DeliveryUrl` or file path (R2), and no credential in the query or userinfo. `playSessionId` is a
 * hint for the server, not a secret. The `Authorization` header is added by the media client (`mediaCallFactory`),
 * which also tells the server the device: a `DELETE` of the encode by play session ends it without a `DeviceId` in the
 * URL (contract test, 12.1 and 12.2).
 */
internal class HlsUrls(
    private val address: ServerAddress,
    private val item: ItemId,
    private val playSession: PlaySessionId?,
) : StreamUrls {
    private fun base(): HttpUrl.Builder =
        checkNotNull(address.baseUrl.toHttpUrlOrNull()) { "A stored address is always a URL" }.newBuilder()

    override fun original(source: MediaSourceId): String =
        base()
            .addPathSegments("Videos/${item.value}/stream")
            .addQueryParameter("static", "true")
            .addQueryParameter("mediaSourceId", source.value)
            .apply { playSession?.let { addQueryParameter("playSessionId", it.value) } }
            .build()
            .toString()

    override fun hls(
        source: MediaSourceId,
        request: PlaybackRequest,
        burnIn: Int?,
    ): String {
        val caps = request.capabilities
        val h264 = caps.videoCodecs.firstOrNull { it.codec == "h264" }
        val maxBitrate = caps.maxStreamingBitrate
        val copy = !request.forceTranscode
        return base()
            .addPathSegments("Videos/${item.value}/master.m3u8")
            .apply {
                param("MediaSourceId", source.value)
                playSession?.let { param("PlaySessionId", it.value) }
                param("VideoCodec", "h264")
                param("AudioCodec", DeviceProfiles.transcodeAudio(caps))
                param("MaxAudioChannels", caps.maxAudioChannels)
                param("TranscodingMaxAudioChannels", caps.maxAudioChannels)
                h264?.let {
                    param("MaxWidth", it.maxWidth)
                    param("MaxHeight", it.maxHeight)
                    it.maxLevel?.let { level -> param("h264-level", level) }
                    if (it.profiles.isNotEmpty()) {
                        // The server's own spelling has no space ("constrainedbaseline").
                        param("h264-profile", profileNames(it).joinToString(",") { name -> name.replace(" ", "") })
                    }
                    param("h264-videobitdepth", it.maxBitDepth)
                }
                param("MaxStreamingBitrate", maxBitrate)
                param("VideoBitrate", (maxBitrate - AUDIO_RESERVE).coerceAtLeast(1))
                param("SegmentContainer", "ts")
                param("BreakOnNonKeyFrames", "True")
                param("StartTimeTicks", PlaybackTicks.of(request.start))
                param("AllowVideoStreamCopy", copy)
                param("AllowAudioStreamCopy", true)
                request.audioIndex?.let { param("AudioStreamIndex", it) }
                burnIn?.let {
                    param("SubtitleStreamIndex", it)
                    param("SubtitleMethod", "Encode")
                }
            }.build()
            .toString()
    }

    override fun subtitle(
        source: MediaSourceId,
        stream: MediaStreamInfo,
    ): String? {
        val text = stream.kind == StreamKind.SUBTITLE && stream.codec?.lowercase() in DeviceProfiles.TEXT_SUBTITLES
        // A dot segment would climb out of the path; no real source ID is one.
        if (!text || stream.delivery != SubtitleDelivery.SEPARATE || source.value in DOT_SEGMENTS) return null
        return base()
            .addPathSegment("Videos")
            .addPathSegment(item.value)
            .addPathSegment(source.value)
            .addPathSegment("Subtitles")
            .addPathSegment(stream.index.toString())
            .addPathSegment("0")
            .addPathSegment("Stream.vtt")
            .build()
            .toString()
    }

    private fun HttpUrl.Builder.param(
        name: String,
        value: Any,
    ) {
        addQueryParameter(name, value.toString())
    }

    private companion object {
        /**
         * What the audio takes of the budget, so the video is asked for the rest: the 192 kb/s the server itself
         * reserves for stereo AAC (`AudioBitrate=192000` in its own transcoding URL, 12.1).
         */
        const val AUDIO_RESERVE = 192_000

        val DOT_SEGMENTS = setOf(".", "..")
    }
}
