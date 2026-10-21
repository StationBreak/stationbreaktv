// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

/**
 * The one vocabulary for codec, container and profile names. It is a neutral convention, ffmpeg's naming in lowercase
 * (`h264`, `hevc`, `eac3`, `mpeg2video`, `constrained baseline`), which is also what Jellyfin sends; it is not any
 * player's or server's own spelling. Adapters translate to and from it at their edge.
 *
 * Only the device-capability side is restricted to the known names ([DeviceCapabilities], [VideoCodecSupport]): a name
 * we don't list there is a bug in the probe. What a server reports about a file ([MediaStreamInfo.codec]) is never
 * restricted, because a server may name a codec we have never heard of; [normalize] only folds its case.
 */
object Codecs {
    const val H264: String = "h264"
    const val HEVC: String = "hevc"
    const val VP9: String = "vp9"
    const val AV1: String = "av1"
    const val MPEG2: String = "mpeg2video"
    const val MPEG4: String = "mpeg4"
    const val VC1: String = "vc1"

    const val AAC: String = "aac"
    const val MP3: String = "mp3"
    const val AC3: String = "ac3"
    const val EAC3: String = "eac3"
    const val DTS: String = "dts"
    const val TRUEHD: String = "truehd"
    const val OPUS: String = "opus"
    const val VORBIS: String = "vorbis"
    const val FLAC: String = "flac"

    const val MP4: String = "mp4"
    const val M4V: String = "m4v"
    const val MOV: String = "mov"
    const val MKV: String = "mkv"
    const val WEBM: String = "webm"
    const val TS: String = "ts"
    const val MPEGTS: String = "mpegts"
    const val AVI: String = "avi"

    const val BASELINE: String = "baseline"
    const val CONSTRAINED_BASELINE: String = "constrained baseline"
    const val MAIN: String = "main"
    const val HIGH: String = "high"
    const val MAIN_10: String = "main 10"
    const val HIGH_10: String = "high 10"

    val VIDEO: Set<String> = setOf(H264, HEVC, VP9, AV1, MPEG2, MPEG4, VC1)
    val AUDIO: Set<String> = setOf(AAC, MP3, AC3, EAC3, DTS, TRUEHD, OPUS, VORBIS, FLAC)

    /** What an HDMI sink can be handed as it is. */
    val PASSTHROUGH_AUDIO: Set<String> = setOf(AC3, EAC3, DTS, TRUEHD)
    val CONTAINERS: Set<String> = setOf(MP4, M4V, MOV, MKV, WEBM, TS, MPEGTS, AVI)

    val H264_PROFILES: Set<String> = setOf(BASELINE, CONSTRAINED_BASELINE, MAIN, HIGH, HIGH_10)
    val HEVC_PROFILES: Set<String> = setOf(MAIN, MAIN_10)

    /** Subtitle formats read as text: in the stream, or as WebVTT the server converts to. */
    val TEXT_SUBTITLES: Set<String> = setOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "mov_text")

    /** Subtitle formats that are pictures: the server burns them into the video when one is selected. */
    val IMAGE_SUBTITLES: Set<String> = setOf("pgs", "pgssub", "dvdsub", "dvd_subtitle", "vobsub")

    /** [name] in the vocabulary's form (trimmed, lowercase); a name we don't know passes through, only folded. */
    fun normalize(name: String?): String? = name?.trim()?.lowercase()

    fun isTextSubtitle(codec: String?): Boolean = normalize(codec) in TEXT_SUBTITLES

    fun isImageSubtitle(codec: String?): Boolean = normalize(codec) in IMAGE_SUBTITLES

    internal fun requireKnown(
        what: String,
        names: Set<String>,
        known: Set<String>,
    ) {
        val unknown = names - known
        require(unknown.isEmpty()) { "Unknown $what: ${unknown.sorted()}" }
    }
}
