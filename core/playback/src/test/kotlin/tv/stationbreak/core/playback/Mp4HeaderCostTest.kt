// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.playback.PlaybackTestData.audio
import tv.stationbreak.core.playback.PlaybackTestData.source
import tv.stationbreak.core.playback.PlaybackTestData.subtitle
import tv.stationbreak.core.playback.PlaybackTestData.video
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class Mp4HeaderCostTest {
    private fun film(
        runtime: Duration?,
        container: String? = Codecs.MP4,
        streams: List<MediaStreamInfo>,
    ): MediaSource = source(container = container, streams = streams).copy(runtime = runtime)

    private fun video24() = video().copy(frameRate = 24.0)

    private fun aac(index: Int) = audio(index).copy(sampleRate = 48_000)

    @Test
    fun samplesAreTheRuntimeTimesEachTracksSamplesPerSecond() {
        // The measured file: 2 h, 24 fps video and four 48 kHz AAC tracks (ffprobe: 172800 + 4 x 337501 frames).
        val measured = film(2.hours, streams = listOf(video24(), aac(1), aac(2), aac(3), aac(4)))
        assertEquals(172_800L + 4 * 337_500L, Mp4HeaderCost.samples(measured))
        assertEquals(1_522_800L * Mp4HeaderCost.BYTES_PER_SAMPLE, Mp4HeaderCost.estimatedBytes(measured))
    }

    @Test
    fun eachAudioCodecCountsItsOwnFrameLength() {
        fun perHour(codec: String): Long {
            val track = audio(1, codec).copy(sampleRate = 48_000)
            return Mp4HeaderCost.samples(film(1.hours, streams = listOf(track)))
        }
        assertEquals(168_750L, perHour(Codecs.AAC))
        assertEquals(112_500L, perHour(Codecs.AC3))
        assertEquals(112_500L, perHour(Codecs.EAC3))
        assertEquals(150_000L, perHour(Codecs.MP3))
        assertEquals(337_500L, perHour(Codecs.DTS))
        assertEquals(4_320_000L, perHour(Codecs.TRUEHD))
        assertEquals(168_750L, perHour("somethingnew"))
    }

    @Test
    fun anUnreportedRateTakesTheDefaults() {
        val bare = film(1.hours, streams = listOf(video(), audio(1)))
        assertEquals(3_600L * 30 + 168_750L, Mp4HeaderCost.samples(bare))
    }

    @Test
    fun subtitlesAndExternalTracksAreLeftOut() {
        val external = aac(2).copy(isExternal = true)
        val plain = film(1.hours, streams = listOf(video24(), aac(1)))
        val more = film(1.hours, streams = listOf(video24(), aac(1), subtitle(3), external))
        assertEquals(Mp4HeaderCost.samples(plain), Mp4HeaderCost.samples(more))
    }

    @Test
    fun onlyTheMp4FamilyHasAHeaderCost() {
        val tracks = listOf(video24(), aac(1), aac(2), aac(3), aac(4))
        assertTrue(Mp4HeaderCost.estimatedBytes(film(3.hours, "mov,mp4,m4a,3gp,3g2,mj2", tracks)) > 0)
        assertTrue(Mp4HeaderCost.estimatedBytes(film(3.hours, "M4V", tracks)) > 0)
        assertEquals(0L, Mp4HeaderCost.estimatedBytes(film(3.hours, Codecs.MKV, tracks)))
        assertEquals(0L, Mp4HeaderCost.estimatedBytes(film(3.hours, null, tracks)))
        assertEquals("no runtime, no estimate", 0L, Mp4HeaderCost.estimatedBytes(film(null, Codecs.MP4, tracks)))
    }

    @Test
    fun onA192MibHeapAnOrdinaryFilmFitsAndAManyTrackEpicDoesNot() {
        val budget = Mp4HeaderCost.budgetFor(192L * 1_024 * 1_024)
        // Two hours twenty, one video and two AAC tracks: about 0.96 M samples, 59 MiB.
        assertTrue(Mp4HeaderCost.fits(film(140.minutes, streams = listOf(video24(), aac(1), aac(2))), budget))
        // Two hours, four AAC tracks (the measured file, 83 MiB to parse on the JVM): refused.
        val fourTracks = film(2.hours, streams = listOf(video24(), aac(1), aac(2), aac(3), aac(4)))
        assertFalse(Mp4HeaderCost.fits(fourTracks, budget))
        // The same tracks in Matroska cost nothing here.
        assertTrue(Mp4HeaderCost.fits(fourTracks.copy(container = Codecs.MKV), budget))
    }
}
