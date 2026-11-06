// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Spec 24: a standby is used only for the source it was built from; the start and the handle aside. */
class StandbySourceTest {
    private val held =
        PlayerSource(
            url = "https://server.example/a.mp4",
            isHls = false,
            playerStart = 5.seconds,
            subtitles = listOf(SeparateSubtitle("https://server.example/a.vtt", "eng")),
            audioIndex = 1,
            subtitleIndex = null,
            directPlayTracks = true,
            audioPick = TrackPick(0, "eng", "aac"),
        )

    @Test
    fun theStartAndTheHandleDoNotCount() {
        assertTrue(sameStandbySource(held, held.copy(playerStart = 9.seconds, warmKey = "w3")))
        assertTrue(sameStandbySource(held.copy(warmKey = "w1"), held.copy(warmKey = "w2")))
    }

    @Test
    fun anyOtherDifferenceIsAnotherSource() {
        val others =
            listOf(
                held.copy(url = "https://server.example/b.mp4"),
                held.copy(isHls = true),
                held.copy(subtitles = emptyList()),
                held.copy(subtitles = listOf(SeparateSubtitle("https://server.example/a.vtt", "fre"))),
                held.copy(audioIndex = 2),
                held.copy(subtitleIndex = 3),
                held.copy(directPlayTracks = false),
                held.copy(audioPick = TrackPick(1, "eng", "aac")),
                held.copy(subtitlePick = TrackPick(0, "eng", null)),
            )
        others.forEach { assertFalse("used for $it", sameStandbySource(held, it)) }
    }

    @Test
    fun noStandbySourceIsNoMatch() {
        assertFalse(sameStandbySource(null, held))
    }
}
