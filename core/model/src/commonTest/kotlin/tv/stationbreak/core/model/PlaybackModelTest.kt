// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class PlaybackModelTest {
    private val capabilities =
        DeviceCapabilities(
            containers = setOf("mp4", "mkv"),
            videoCodecs = emptyList(),
            audioDecoders = setOf("aac"),
            audioPassthrough = emptySet(),
            maxAudioChannels = 6,
        )

    private fun request(start: kotlin.time.Duration) =
        PlaybackRequest(capabilities, start, audioIndex = null, subtitleIndex = null, forceTranscode = false)

    private fun stream(
        index: Int,
        kind: StreamKind,
        delivery: SubtitleDelivery? = null,
    ) = MediaStreamInfo(index, kind, "x", "eng", null, null, null, false, false, false, delivery)

    @Test
    fun aNegativeStartIsRejected() {
        assertFailsWith<IllegalArgumentException> { request((-1).seconds) }
    }

    @Test
    fun aZeroStartIsAccepted() {
        assertEquals(0.seconds, request(0.seconds).start)
    }

    @Test
    fun theMaximumStreamingBitrateDefaultsToForty() {
        assertEquals(40_000_000, capabilities.maxStreamingBitrate)
    }

    @Test
    fun streamsKeepTheirServerIndex() {
        val source =
            MediaSource(
                MediaSourceId("s"),
                null,
                "mkv",
                null,
                true,
                true,
                streams =
                    listOf(
                        stream(3, StreamKind.AUDIO),
                        stream(7, StreamKind.SUBTITLE, SubtitleDelivery.SEPARATE),
                    ),
            )
        assertEquals(listOf(3, 7), source.streams.map { it.index })
    }

    @Test
    fun subtitleDeliveryIsNullForNonSubtitleStreams() {
        assertNull(stream(0, StreamKind.VIDEO).delivery)
        assertNull(stream(1, StreamKind.AUDIO).delivery)
        assertEquals(SubtitleDelivery.BURNED_IN, stream(2, StreamKind.SUBTITLE, SubtitleDelivery.BURNED_IN).delivery)
    }
}
