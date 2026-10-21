// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback.probe

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioFormat
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities

/**
 * The platform's answers, read through `MediaCodecList`, the default display and Media3's [AudioCapabilities].
 * Everything here is available on API 25; newer calls are behind an SDK check.
 */
@UnstableApi
class AndroidCodecInventory(
    context: Context,
) : CodecInventory {
    private val appContext: Context = context.applicationContext ?: context

    private val decoders: List<MediaCodecInfo> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }
    }

    override fun videoDecoders(): List<DecoderInfo> =
        decoders.flatMap { info ->
            info.supportedTypes
                .filter { it.startsWith(VIDEO_PREFIX, ignoreCase = true) }
                .mapNotNull { mime -> decoderInfo(info, mime) }
        }

    override fun audioDecoders(): Set<String> =
        decoders
            .flatMap { it.supportedTypes.asList() }
            .filter { it.startsWith(AUDIO_PREFIX, ignoreCase = true) }
            .map { it.lowercase() }
            .toSet()

    override fun passthroughEncodings(): Set<Int> {
        val capabilities = AudioCapabilities.getCapabilities(appContext)
        return PASSTHROUGH_CANDIDATES.filterTo(mutableSetOf()) { capabilities.supportsEncoding(it) }
    }

    override fun maxChannels(): Int = AudioCapabilities.getCapabilities(appContext).maxChannelCount

    override fun display(): DisplayInfo {
        val display =
            appContext.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                ?: return DisplayInfo(FALLBACK_WIDTH, FALLBACK_HEIGHT, emptySet())
        val mode = display.mode
        return DisplayInfo(mode.physicalWidth, mode.physicalHeight, hdrTypesOf(display))
    }

    @Suppress("DEPRECATION")
    private fun hdrTypesOf(display: Display): Set<Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            display.mode.supportedHdrTypes.toSet()
        } else {
            display.hdrCapabilities
                ?.supportedHdrTypes
                ?.toSet()
                .orEmpty()
        }

    private fun decoderInfo(
        info: MediaCodecInfo,
        mime: String,
    ): DecoderInfo? {
        val capabilities = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
        val video = capabilities?.videoCapabilities
        val levels = capabilities?.profileLevels
        return if (video == null || levels == null) {
            null
        } else {
            DecoderInfo(
                mime = mime.lowercase(),
                profiles = levels.mapTo(mutableSetOf()) { it.profile },
                maxLevel = levels.maxOfOrNull { it.level },
                maxWidth = video.supportedWidths.upper,
                maxHeight = video.supportedHeights.upper,
                softwareOnly = isSoftwareOnly(info),
            )
        }
    }

    /**
     * API 29+ asks the platform. Below that, the name check is Media3 1.11.1's `MediaCodecUtil.isSoftwareOnly`
     * (`androidx.media3.exoplayer.mediacodec`): lowercase name, `arc.` is hardware; `omx.google.`, `omx.ffmpeg.`,
     * `omx.sec.` with `.sw.`, `omx.qcom.video.decoder.hevcswvdec`, `c2.android.` and `c2.google.` are software; and
     * a name starting with neither `omx.` nor `c2.` is taken as software.
     */
    private fun isSoftwareOnly(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isSoftwareOnly
        val name = info.name.lowercase()
        return when {
            name.startsWith("arc.") -> false
            SOFTWARE_PREFIXES.any { name.startsWith(it) } -> true
            name.startsWith("omx.sec.") && ".sw." in name -> true
            name == "omx.qcom.video.decoder.hevcswvdec" -> true
            else -> !(name.startsWith("omx.") || name.startsWith("c2."))
        }
    }

    private companion object {
        val SOFTWARE_PREFIXES = listOf("omx.google.", "omx.ffmpeg.", "c2.android.", "c2.google.")

        const val VIDEO_PREFIX = "video/"
        const val AUDIO_PREFIX = "audio/"
        const val FALLBACK_WIDTH = 1920
        const val FALLBACK_HEIGHT = 1080

        val PASSTHROUGH_CANDIDATES =
            listOf(
                AudioFormat.ENCODING_AC3,
                AudioFormat.ENCODING_E_AC3,
                AudioFormat.ENCODING_E_AC3_JOC,
                AudioFormat.ENCODING_DTS,
                AudioFormat.ENCODING_DTS_HD,
                AudioFormat.ENCODING_DOLBY_TRUEHD,
            )
    }
}
