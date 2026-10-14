// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

/**
 * The device profile `playbackInfo` sends: a placeholder that claims only what every Fire TV decodes (H.264 video, AAC,
 * AC-3 or MP3 audio, in MP4 or Matroska) and asks for no transcoding. F1.6's capability probe replaces it with what the
 * device really supports; until then it is enough for the server to list the sources. No credential, no device name.
 */
internal object PlaybackProfile {
    const val JSON =
        "{\"Name\":\"StationBreak placeholder\"," +
            "\"MaxStreamingBitrate\":40000000," +
            "\"DirectPlayProfiles\":[" +
            "{\"Container\":\"mp4,mkv\",\"Type\":\"Video\",\"VideoCodec\":\"h264\",\"AudioCodec\":\"aac,ac3,mp3\"}]," +
            "\"TranscodingProfiles\":[]," +
            "\"ContainerProfiles\":[]," +
            "\"CodecProfiles\":[]," +
            "\"SubtitleProfiles\":[]}"

    /** The whole `PlaybackInfo` request body. */
    val BODY: ByteArray =
        (
            "{\"DeviceProfile\":$JSON,\"EnableDirectPlay\":true,\"EnableDirectStream\":true," +
                "\"EnableTranscoding\":false}"
        ).toByteArray(Charsets.UTF_8)
}
