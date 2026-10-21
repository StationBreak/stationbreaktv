// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback.probe

/**
 * What the platform says about this device, as plain values the [CapabilityProbe] can turn into
 * `DeviceCapabilities`. The Android implementation is [AndroidCodecInventory]; tests use a fake.
 */
interface CodecInventory {
    /** Video decoders only: encoders are never listed. */
    fun videoDecoders(): List<DecoderInfo>

    /** The MIME types of the audio decoders. */
    fun audioDecoders(): Set<String>

    /** `AudioFormat.ENCODING_*` values the HDMI sink accepts as they are (Media3 `AudioCapabilities`). */
    fun passthroughEncodings(): Set<Int>

    fun maxChannels(): Int

    fun display(): DisplayInfo
}

/**
 * One video decoder. [profiles] are `MediaCodecInfo.CodecProfileLevel` profile constants and [maxLevel] the highest
 * level constant it reports for the codec (null when it reports none); the probe translates both.
 */
data class DecoderInfo(
    val mime: String,
    val profiles: Set<Int>,
    val maxLevel: Int?,
    val maxWidth: Int,
    val maxHeight: Int,
)

/** The display's current mode, in pixels, and the `Display.HdrCapabilities.HDR_TYPE_*` values it supports. */
data class DisplayInfo(
    val width: Int,
    val height: Int,
    val hdrTypes: Set<Int>,
)
