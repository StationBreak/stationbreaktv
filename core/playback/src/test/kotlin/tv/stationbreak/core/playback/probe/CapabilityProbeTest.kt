// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback.probe

import android.media.AudioFormat
import android.media.MediaCodecInfo.CodecProfileLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.DeviceCapabilities

class CapabilityProbeTest {
    private class FakeInventory(
        var video: List<DecoderInfo> = emptyList(),
        var audio: Set<String> = emptySet(),
        var passthrough: Set<Int> = emptySet(),
        var channels: Int = 2,
        var display: DisplayInfo = DisplayInfo(1920, 1080, emptySet()),
    ) : CodecInventory {
        override fun videoDecoders() = video

        override fun audioDecoders() = audio

        override fun passthroughEncodings() = passthrough

        override fun maxChannels() = channels

        override fun display() = display
    }

    private fun avc(
        profiles: Set<Int> = setOf(CodecProfileLevel.AVCProfileHigh),
        level: Int? = CodecProfileLevel.AVCLevel41,
        width: Int = 1920,
        height: Int = 1080,
    ) = DecoderInfo("video/avc", profiles, level, width, height)

    private fun probeOf(inventory: FakeInventory) = CapabilityProbe(inventory).probe()

    @Test
    fun anAvcDecoderBecomesH264WithItsLevel() {
        val caps = probeOf(FakeInventory(video = listOf(avc())))

        val h264 = caps.videoCodecs.single()
        assertEquals(Codecs.H264, h264.codec)
        assertEquals(setOf(Codecs.HIGH), h264.profiles)
        assertEquals(41, h264.maxLevel)
        assertEquals(1920, h264.maxWidth)
        assertEquals(1080, h264.maxHeight)
        assertEquals(8, h264.maxBitDepth)
    }

    @Test
    fun aDisplayCapsTheResolution() {
        val inventory =
            FakeInventory(
                video = listOf(avc(width = 3840, height = 2160)),
                display = DisplayInfo(1920, 1080, emptySet()),
            )

        val h264 = probeOf(inventory).videoCodecs.single()

        assertEquals(1920, h264.maxWidth)
        assertEquals(1080, h264.maxHeight)
    }

    @Test
    fun aSmallerDecoderIsNotRaisedToTheDisplay() {
        val inventory = FakeInventory(video = listOf(avc(width = 1280, height = 720)))

        val h264 = probeOf(inventory).videoCodecs.single()

        assertEquals(1280, h264.maxWidth)
        assertEquals(720, h264.maxHeight)
    }

    @Test
    fun aBaselineDecoderAlsoAdvertisesConstrainedBaseline() {
        val baseline = probeOf(FakeInventory(video = listOf(avc(setOf(CodecProfileLevel.AVCProfileBaseline)))))
        assertEquals(setOf(Codecs.BASELINE, Codecs.CONSTRAINED_BASELINE), baseline.videoCodecs.single().profiles)

        val constrained =
            probeOf(FakeInventory(video = listOf(avc(setOf(CodecProfileLevel.AVCProfileConstrainedBaseline)))))
        assertEquals(setOf(Codecs.CONSTRAINED_BASELINE), constrained.videoCodecs.single().profiles)
    }

    @Test
    fun h264ProfilesMapToTheVocabulary() {
        val everything =
            setOf(
                CodecProfileLevel.AVCProfileMain,
                CodecProfileLevel.AVCProfileHigh,
                CodecProfileLevel.AVCProfileHigh10,
            )

        val h264 = probeOf(FakeInventory(video = listOf(avc(everything)))).videoCodecs.single()

        assertEquals(setOf(Codecs.MAIN, Codecs.HIGH, Codecs.HIGH_10), h264.profiles)
        assertEquals(10, h264.maxBitDepth)
    }

    @Test
    fun hevcProfilesAndLevelMapToTheVocabulary() {
        val hevc =
            DecoderInfo(
                "video/hevc",
                setOf(CodecProfileLevel.HEVCProfileMain, CodecProfileLevel.HEVCProfileMain10),
                CodecProfileLevel.HEVCMainTierLevel41,
                3840,
                2160,
            )

        val support =
            probeOf(FakeInventory(video = listOf(hevc), display = DisplayInfo(3840, 2160, emptySet())))
                .videoCodecs
                .single()

        assertEquals(Codecs.HEVC, support.codec)
        assertEquals(setOf(Codecs.MAIN, Codecs.MAIN_10), support.profiles)
        assertEquals(123, support.maxLevel)
        assertEquals(10, support.maxBitDepth)
    }

    @Test
    fun unknownProfilesAreDropped() {
        val odd =
            setOf(
                CodecProfileLevel.AVCProfileExtended,
                CodecProfileLevel.AVCProfileHigh422,
                CodecProfileLevel.AVCProfileMain,
                -1,
            )

        val h264 = probeOf(FakeInventory(video = listOf(avc(odd)))).videoCodecs.single()

        assertEquals(setOf(Codecs.MAIN), h264.profiles)
    }

    @Test
    fun otherVideoCodecsCarryNoProfilesOrLevel() {
        val vp9 = DecoderInfo("video/x-vnd.on2.vp9", setOf(CodecProfileLevel.VP9Profile0), 1, 1920, 1080)

        val support = probeOf(FakeInventory(video = listOf(vp9))).videoCodecs.single()

        assertEquals(Codecs.VP9, support.codec)
        assertEquals(emptySet<String>(), support.profiles)
        assertNull(support.maxLevel)
    }

    @Test
    fun twoDecodersOfOneCodecAreMerged() {
        val software = avc(setOf(CodecProfileLevel.AVCProfileMain), CodecProfileLevel.AVCLevel31, 1280, 720)
        val hardware = avc(setOf(CodecProfileLevel.AVCProfileHigh), CodecProfileLevel.AVCLevel41, 1920, 1080)

        val h264 = probeOf(FakeInventory(video = listOf(software, hardware))).videoCodecs.single()

        assertEquals(setOf(Codecs.MAIN, Codecs.HIGH), h264.profiles)
        assertEquals(41, h264.maxLevel)
        assertEquals(1920, h264.maxWidth)
    }

    @Test
    fun passthroughComesFromTheSink() {
        val inventory =
            FakeInventory(
                passthrough =
                    setOf(
                        AudioFormat.ENCODING_AC3,
                        AudioFormat.ENCODING_E_AC3,
                        AudioFormat.ENCODING_E_AC3_JOC,
                        AudioFormat.ENCODING_DTS,
                        AudioFormat.ENCODING_DTS_HD,
                        AudioFormat.ENCODING_DOLBY_TRUEHD,
                        AudioFormat.ENCODING_PCM_16BIT,
                        AudioFormat.ENCODING_AAC_LC,
                    ),
                channels = 8,
            )

        val caps = probeOf(inventory)

        assertEquals(setOf(Codecs.AC3, Codecs.EAC3, Codecs.DTS, Codecs.TRUEHD), caps.audioPassthrough)
        assertEquals(8, caps.maxAudioChannels)
    }

    @Test
    fun withSinkChangesOnlyAudio() {
        val inventory =
            FakeInventory(
                video = listOf(avc()),
                audio = setOf("audio/mp4a-latm"),
                passthrough = setOf(AudioFormat.ENCODING_AC3),
                channels = 6,
            )
        val probe = CapabilityProbe(inventory)
        val before = probe.probe()

        inventory.passthrough = setOf(AudioFormat.ENCODING_E_AC3)
        inventory.channels = 2
        inventory.video = emptyList()
        inventory.audio = emptySet()
        val after = probe.withSink(before)

        assertEquals(setOf(Codecs.EAC3), after.audioPassthrough)
        assertEquals(2, after.maxAudioChannels)
        assertEquals(before.containers, after.containers)
        assertEquals(before.videoCodecs, after.videoCodecs)
        assertEquals(before.audioDecoders, after.audioDecoders)
        assertEquals(before.maxStreamingBitrate, after.maxStreamingBitrate)
    }

    @Test
    fun audioDecodersAreNamedFromTheirMimeTypes() {
        val mimes =
            setOf(
                "audio/mp4a-latm",
                "audio/mpeg",
                "audio/ac3",
                "audio/eac3",
                "audio/opus",
                "audio/flac",
                "audio/vorbis",
            )

        val caps = probeOf(FakeInventory(audio = mimes))

        val expected = setOf(Codecs.AAC, Codecs.MP3, Codecs.AC3, Codecs.EAC3, Codecs.OPUS, Codecs.FLAC, Codecs.VORBIS)
        assertEquals(expected, caps.audioDecoders)
    }

    @Test
    fun unknownMimesAreIgnored() {
        val inventory =
            FakeInventory(
                video = listOf(DecoderInfo("video/x-unheard-of", emptySet(), null, 1920, 1080), avc()),
                audio = setOf("audio/x-unheard-of", "audio/opus"),
            )

        val caps = probeOf(inventory)

        assertEquals(listOf(Codecs.H264), caps.videoCodecs.map { it.codec })
        assertEquals(setOf(Codecs.OPUS), caps.audioDecoders)
    }

    @Test
    fun aviIsAContainerOnlyWithAnMpeg4Decoder() {
        val without = probeOf(FakeInventory(video = listOf(avc())))
        assertFalse(Codecs.AVI in without.containers)

        val mpeg4 = DecoderInfo("video/mp4v-es", emptySet(), null, 1920, 1080)
        val with = probeOf(FakeInventory(video = listOf(mpeg4)))
        assertTrue(Codecs.AVI in with.containers)
    }

    @Test
    fun theContainersAndBitrateAreTheFixedSet() {
        val caps = probeOf(FakeInventory())

        val expected = setOf(Codecs.MP4, Codecs.M4V, Codecs.MKV, Codecs.WEBM, Codecs.TS, Codecs.MPEGTS)
        assertEquals(expected, caps.containers)
        assertEquals(40_000_000, caps.maxStreamingBitrate)
        assertEquals(DeviceCapabilities.DEFAULT_MAX_STREAMING_BITRATE, caps.maxStreamingBitrate)
    }
}
