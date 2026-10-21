// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecVocabularyTest {
    private fun caps(
        containers: Set<String> = setOf(Codecs.MP4),
        video: List<VideoCodecSupport> = emptyList(),
        decoders: Set<String> = setOf(Codecs.AAC),
        passthrough: Set<String> = emptySet(),
    ) = DeviceCapabilities(containers, video, decoders, passthrough, maxAudioChannels = 2)

    private fun h264(vararg profiles: String) =
        VideoCodecSupport(
            codec = Codecs.H264,
            profiles = profiles.toSet(),
            maxLevel = 41,
            maxWidth = 1920,
            maxHeight = 1080,
            maxBitDepth = 8,
            hdr = emptySet(),
        )

    @Test
    fun knownNamesAreAccepted() {
        val built =
            caps(
                containers = setOf("mp4", "mkv", "ts"),
                video = listOf(h264("baseline", "constrained baseline", "main", "high")),
                decoders = setOf("aac", "mp3", "ac3"),
                passthrough = setOf("ac3", "eac3", "dts", "truehd"),
            )
        assertEquals(setOf("mp4", "mkv", "ts"), built.containers)
    }

    @Test
    fun anUnknownContainerIsRejected() {
        assertFailsWith<IllegalArgumentException> { caps(containers = setOf("MP4")) }
        assertFailsWith<IllegalArgumentException> { caps(containers = setOf("matroska")) }
    }

    @Test
    fun anUnknownVideoCodecIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            VideoCodecSupport("H264", emptySet(), null, 1, 1, 8, emptySet())
        }
        assertFailsWith<IllegalArgumentException> {
            VideoCodecSupport("avc", emptySet(), null, 1, 1, 8, emptySet())
        }
    }

    @Test
    fun anUnknownAudioDecoderIsRejected() {
        assertFailsWith<IllegalArgumentException> { caps(decoders = setOf("AAC")) }
        assertFailsWith<IllegalArgumentException> { caps(decoders = setOf("mp4a")) }
    }

    @Test
    fun passthroughIsOnlyTheFourSinkFormats() {
        assertFailsWith<IllegalArgumentException> { caps(passthrough = setOf("aac")) }
        assertFailsWith<IllegalArgumentException> { caps(passthrough = setOf("DTS")) }
    }

    @Test
    fun anUnknownH264ProfileIsRejected() {
        assertFailsWith<IllegalArgumentException> { h264("Baseline") }
        assertFailsWith<IllegalArgumentException> { h264("constrainedbaseline") }
    }

    @Test
    fun streamsOfUnknownCodecsStillBuild() {
        val stream =
            MediaStreamInfo(1, StreamKind.AUDIO, "someNewCodec", null, null, null, null, false, false, false, null)
        assertEquals("someNewCodec", stream.codec)
    }

    @Test
    fun normalizeFoldsCaseAndSpace() {
        assertEquals("pgssub", Codecs.normalize(" PGSSUB "))
        assertNull(Codecs.normalize(null))
    }

    @Test
    fun subtitleFormatsAreTextOrPicture() {
        assertTrue(Codecs.isTextSubtitle("SubRip"))
        assertTrue(Codecs.isImageSubtitle("PGSSUB"))
        assertFalse(Codecs.isTextSubtitle("pgs"))
        assertFalse(Codecs.isTextSubtitle(null))
        assertFalse(Codecs.isImageSubtitle("srt"))
        assertTrue(Codecs.TEXT_SUBTITLES.intersect(Codecs.IMAGE_SUBTITLES).isEmpty())
    }
}
