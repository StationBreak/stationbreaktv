// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.StreamKind
import tv.stationbreak.core.model.SubtitleDelivery
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleUrlTest {
    private val jellyfin = FakeJellyfin()

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun subtitle(
        codec: String,
        delivery: SubtitleDelivery?,
        subpath: String = "",
    ): String? =
        runBlocking {
            jellyfin.on("POST", "/PlaybackInfo") { jellyfin.json("{\"MediaSources\":[{\"Id\":\"src1\"}]}") }
            val session = testSession(jellyfin.address.copy(subpath = subpath))
            val info = testAdapter().playbackInfo(session, ItemId("i1")).getOrNull()!!
            val stream =
                MediaStreamInfo(3, StreamKind.SUBTITLE, codec, "eng", null, null, null, false, false, false, delivery)
            info.streams.subtitle(MediaSourceId("src1"), stream)
        }

    @Test
    fun aSeparateTextStreamGetsAVttUrl() {
        val base = "http://127.0.0.1:${jellyfin.server.port}"

        assertEquals("$base/Videos/i1/src1/Subtitles/3/0/Stream.vtt", subtitle("subrip", SubtitleDelivery.SEPARATE))
        assertEquals(
            "$base/media/jf/Videos/i1/src1/Subtitles/3/0/Stream.vtt",
            subtitle("ass", SubtitleDelivery.SEPARATE, subpath = "/media/jf"),
        )
    }

    @Test
    fun anImageOrInStreamSubtitleGetsNone() {
        assertNull(subtitle("pgssub", SubtitleDelivery.SEPARATE))
        assertNull(subtitle("subrip", SubtitleDelivery.IN_STREAM))
        assertNull(subtitle("subrip", SubtitleDelivery.BURNED_IN))
        assertNull(subtitle("subrip", SubtitleDelivery.NONE))
        assertNull(subtitle("subrip", null))
    }
}
