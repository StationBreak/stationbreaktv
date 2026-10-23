// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.DeviceCapabilities
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.StreamKind
import tv.stationbreak.core.model.StreamUrls
import tv.stationbreak.core.model.SubtitleDelivery
import tv.stationbreak.core.model.VideoCodecSupport

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
            .addPathSegment("Videos")
            .addPathSegment(segment(item.value))
            .addPathSegment("stream")
            .addQueryParameter("static", "true")
            .addQueryParameter("mediaSourceId", segment(source.value))
            .apply { playSession?.let { addQueryParameter("playSessionId", it.value) } }
            .build()
            .toString()

    override fun hls(
        source: MediaSource,
        audioIndex: Int?,
        burnIn: Int?,
        limits: DeviceCapabilities,
        forceTranscode: Boolean,
    ): String {
        // fMP4 and HEVC only for an HEVC source that will be copied; everything else, every forced transcode included,
        // is H.264 in MPEG-TS, so the fallback has a real way out (ADR-0016, update 2026-10-22, R33).
        val hevc = hevcCopy(source, limits, forceTranscode)
        val video = listOfNotNull(limits.videoCodecs.firstOrNull { it.codec == Codecs.H264 }, hevc)
        val maxBitrate = limits.maxStreamingBitrate
        // The server resolved the user's own track preference into the source's default; without this the server would
        // fall back to the file's first track.
        val audio = audioIndex ?: source.defaultAudioIndex
        return base()
            .addPathSegment("Videos")
            .addPathSegment(segment(item.value))
            .addPathSegment("master.m3u8")
            .apply {
                param("MediaSourceId", segment(source.id.value))
                playSession?.let { param("PlaySessionId", it.value) }
                param("VideoCodec", if (hevc != null) "${Codecs.H264},${Codecs.HEVC}" else Codecs.H264)
                param("AudioCodec", DeviceProfiles.transcodeAudio(limits, fmp4 = hevc != null))
                param("MaxAudioChannels", limits.maxAudioChannels)
                param("TranscodingMaxAudioChannels", limits.maxAudioChannels)
                // The size limit of the codec the video will be in: HEVC's for a copied HEVC source, else H.264's.
                (hevc ?: video.firstOrNull())?.let {
                    param("MaxWidth", it.maxWidth)
                    param("MaxHeight", it.maxHeight)
                }
                video.forEach { codecParams(it) }
                param("MaxStreamingBitrate", maxBitrate)
                param("VideoBitrate", (maxBitrate - AUDIO_RESERVE).coerceAtLeast(1))
                param("AudioBitrate", AUDIO_RESERVE)
                param("SegmentContainer", if (hevc != null) Codecs.MP4 else Codecs.TS)
                param("BreakOnNonKeyFrames", "True")
                param("AllowVideoStreamCopy", !forceTranscode)
                param("AllowAudioStreamCopy", true)
                audio?.let { param("AudioStreamIndex", it) }
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
        val text = stream.kind == StreamKind.SUBTITLE && Codecs.isTextSubtitle(stream.codec)
        // A dot segment would climb out of the path; no real source ID is one.
        if (!text || stream.delivery != SubtitleDelivery.SEPARATE || source.value in DOT_SEGMENTS) return null
        return base()
            .addPathSegment("Videos")
            .addPathSegment(segment(item.value))
            .addPathSegment(source.value)
            .addPathSegment("Subtitles")
            .addPathSegment(stream.index.toString())
            .addPathSegment("0")
            .addPathSegment("Stream.vtt")
            .build()
            .toString()
    }

    /** An ID that is a dot segment would climb out of the path, however it is encoded; no real ID is one. */
    private fun segment(id: String): String {
        require(id !in DOT_SEGMENTS) { "An ID can't be a dot segment" }
        return id
    }

    /**
     * The device's HEVC support when [source]'s video is HEVC the device decodes within its limits and may be copied
     * ([forceTranscode] off); null otherwise. An unreported limit is not held against the file.
     */
    private fun hevcCopy(
        source: MediaSource,
        limits: DeviceCapabilities,
        forceTranscode: Boolean,
    ): VideoCodecSupport? {
        if (forceTranscode) return null
        val stream = source.streams.firstOrNull { it.kind == StreamKind.VIDEO }
        val video = stream?.takeIf { Codecs.normalize(it.codec) == Codecs.HEVC }
        return limits.videoCodecs.firstOrNull { it.codec == Codecs.HEVC }?.takeIf { video != null && covers(it, video) }
    }

    private fun covers(
        support: VideoCodecSupport,
        video: MediaStreamInfo,
    ): Boolean {
        val profile = Codecs.normalize(video.videoProfile)
        val profileFits = profile == null || support.profiles.isEmpty() || profile in support.profiles
        return profileFits &&
            within(video.videoLevel, support.maxLevel) &&
            within(video.width, support.maxWidth) &&
            within(video.height, support.maxHeight) &&
            within(video.bitDepth, support.maxBitDepth)
    }

    private fun within(
        value: Int?,
        limit: Int?,
    ) = value == null || limit == null || value <= limit

    /** The server's own per-codec names: `h264-level`, `hevc-profile`, ... (profile spelled without spaces). */
    private fun HttpUrl.Builder.codecParams(support: VideoCodecSupport) {
        val codec = support.codec
        support.maxLevel?.let { param("$codec-level", it) }
        if (support.profiles.isNotEmpty()) {
            // The server's own spelling has no space ("constrainedbaseline", "main10").
            param("$codec-profile", profileNames(support).joinToString(",") { it.replace(" ", "") })
        }
        param("$codec-videobitdepth", support.maxBitDepth)
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
         * reserves for stereo AAC (`AudioBitrate=192000` in its own transcoding URL, 12.1 and 12.2). It is also sent as
         * `AudioBitrate`. It assumes stereo AAC: with more channels the server's own audio bitrate would be higher.
         */
        const val AUDIO_RESERVE = 192_000

        val DOT_SEGMENTS = setOf(".", "..")
    }
}
