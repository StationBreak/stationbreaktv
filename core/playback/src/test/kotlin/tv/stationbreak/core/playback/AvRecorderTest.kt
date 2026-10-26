// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvRecorderTest {
    @Test
    fun recordsEachStepInMsSincePlayCall() {
        val av = AvRecorder()
        av.begin(3, calledAt = 1_000)
        av.videoDecoder(3, at = 1_120, initMs = 40)
        av.audioDecoder(3, at = 1_150, initMs = 25)
        av.audioTrack(3, at = 1_300)
        av.underrun(3)
        av.underrun(3)
        av.ready(3, at = 1_900)
        av.audioAdvancing(3, at = 2_050)

        assertEquals(
            AvTimings(
                videoDecInitMs = 120,
                videoDecInitDurMs = 40,
                audioDecInitMs = 150,
                audioDecInitDurMs = 25,
                audioTrackInitMs = 300,
                audioAdvancingMs = 1_050,
                audioUnderruns = 2,
                readyMs = 900,
            ),
            av.finish(3),
        )
    }

    @Test
    fun aStepThatDidNotHappenIsMinusOne() {
        val av = AvRecorder()
        av.begin(1, calledAt = 10)
        av.ready(1, at = 60)

        assertEquals(AvTimings(readyMs = 50), av.finish(1))
        assertEquals(-1L, av.finish(1)?.audioTrackInitMs)
    }

    @Test
    fun theFirstTimeOfAStepCounts() {
        val av = AvRecorder()
        av.begin(1, calledAt = 0)
        av.audioTrack(1, at = 100)
        av.audioTrack(1, at = 400)

        assertEquals(100L, av.finish(1)?.audioTrackInitMs)
    }

    @Test
    fun anEventOfAStaleGenerationIsIgnored() {
        val av = AvRecorder()
        av.begin(1, calledAt = 0)
        av.audioTrack(1, at = 100)
        av.begin(2, calledAt = 1_000)
        av.audioTrack(1, at = 1_050)
        av.underrun(1)
        av.audioDecoder(2, at = 1_200, initMs = 9)

        assertNull(av.finish(1))
        assertEquals(AvTimings(audioDecInitMs = 200, audioDecInitDurMs = 9), av.finish(2))
    }

    @Test
    fun nothingIsRecordedAfterPlaying() {
        val av = AvRecorder()
        av.begin(1, calledAt = 0)
        av.finish(1)
        av.underrun(1)
        av.audioAdvancing(1, at = 500)

        assertEquals(AvTimings(), av.finish(1))
    }
}
