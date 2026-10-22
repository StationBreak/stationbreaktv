// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber", "LongParameterList") // Scripted times and sample values; detekt skips only src/test.

package tv.stationbreak.app.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.SubtitleDelivery
import tv.stationbreak.core.playback.Route
import tv.stationbreak.core.playback.TuneFailure
import tv.stationbreak.core.playback.TuneResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PlaybackResultTest {
    private fun result(
        route: Route = Route.DIRECT_PLAY,
        expected: Route? = Route.DIRECT_PLAY,
        watch: Watch = Watch(stalls = 0, cues = 0, audioAdvancedMs = 12_000),
        subtitle: MediaStreamInfo? = null,
        section: String = "video",
        positionError: Duration? = null,
        outcome: TuneResult? = null,
    ): PlaybackResult {
        val resolved = BenchTestData.resolved(route, subtitle)
        val played = outcome ?: TuneResult.Played(resolved, 300, 400, positionError, null)
        return PlaybackResult.from(section, played, watch, expected, 30_000)
    }

    @Test
    fun theLineHasNoIdTitleOrUrl() {
        val line = result().toJsonLine()
        assertFalse(line, line.contains("Secret"))
        assertFalse(line, line.contains("1999"))
        assertFalse(line, line.contains("abc123"))
        assertFalse(line, line.contains("http"))
        assertTrue(line, line.contains("\"video\":\"hevc 1920x1080 10bit\""))
        assertTrue(line, line.contains("\"audio\":\"dts 6ch\""))
    }

    @Test
    fun theLineNamesItsRunAndItsOrdinalInTheBatch() {
        val line = result().toJsonLine("r1", 3)
        assertTrue(line, line.startsWith("{\"run_id\":\"r1\",\"n\":3,"))
        assertFalse(result().toJsonLine().contains("run_id"))
    }

    @Test
    fun passNeedsTheExpectedRoute() {
        assertTrue(result(route = Route.DIRECT_PLAY, expected = Route.DIRECT_PLAY).pass)
        assertFalse(result(route = Route.TRANSCODE, expected = Route.DIRECT_PLAY).pass)
        // No oracle for this item (a track override): the route is not held against it.
        assertTrue(result(route = Route.TRANSCODE, expected = null).pass)
        // Silent audio or a missed seek still fails.
        assertFalse(result(watch = Watch(0, 0, 2_000)).pass)
        assertFalse(result(positionError = 5.seconds).pass)
    }

    @Test
    fun aMissingSubtitleIsNullNotFalse() {
        val none = result()
        assertEquals("none", none.subtitle)
        assertNull(none.subtitleOk)
        assertTrue(none.toJsonLine().contains("\"subtitleOk\":null"))
        val text = result(subtitle = BenchTestData.textSubtitle(), watch = Watch(0, 0, 12_000))
        assertEquals("text-in-stream", text.subtitle)
        assertEquals(false, text.subtitleOk)
        assertFalse(text.pass)
        val image = result(Route.TRANSCODE, Route.TRANSCODE, subtitle = BenchTestData.imageSubtitle())
        assertEquals("image", image.subtitle)
        assertEquals(true, image.subtitleOk)
        assertTrue(image.burnIn)
    }

    // The rule: a subtitle is an image by its codec, whatever the server does with it. An image must be burned in; a
    // text one needs cues, or the server burning it in is the evidence instead.
    @Test
    fun anImageSubtitleIsDecidedByCodecAndMustBeBurnedIn() {
        val notBurned = BenchTestData.subtitleOf("pgssub", SubtitleDelivery.IN_STREAM)
        val r = result(subtitle = notBurned, watch = Watch(0, 5, 12_000))
        assertEquals("image", r.subtitle)
        assertEquals(false, r.subtitleOk)
        assertFalse(r.burnIn)
    }

    @Test
    fun aTextSubtitleTheServerBurnedInIsOkWithoutCues() {
        val burned = BenchTestData.subtitleOf("subrip", SubtitleDelivery.BURNED_IN)
        val r = result(Route.TRANSCODE, Route.TRANSCODE, subtitle = burned, watch = Watch(0, 0, 12_000))
        assertEquals("text-in-stream", r.subtitle)
        assertTrue(r.burnIn)
        assertEquals(true, r.subtitleOk)
        assertEquals(false, result(subtitle = BenchTestData.textSubtitle(), watch = Watch(0, 0, 12_000)).subtitleOk)
    }

    // Ruling R30: the real section starts at a random point, so a text subtitle may have no cue in the window.
    @Test
    fun aRealTextSubtitleWithNoCueIsReportedButDoesNotFailTheRow() {
        val silent = Watch(0, 0, 12_000)
        val text = BenchTestData.textSubtitle()
        val real = result(subtitle = text, watch = silent, section = PlaybackResult.REAL_SECTION)
        assertEquals(false, real.subtitleOk)
        assertTrue(real.toJsonLine().contains("\"subtitleOk\":false"))
        assertTrue(real.pass)
        val synthetic = result(subtitle = text, watch = silent, section = "synthetic")
        assertEquals(false, synthetic.subtitleOk)
        assertFalse(synthetic.pass)
    }

    @Test
    fun aRealImageSubtitleThatWasNotBurnedInStillFails() {
        val notBurned = BenchTestData.subtitleOf("pgssub", SubtitleDelivery.IN_STREAM)
        val r = result(subtitle = notBurned, watch = Watch(0, 5, 12_000), section = PlaybackResult.REAL_SECTION)
        assertEquals(false, r.subtitleOk)
        assertFalse(r.pass)
    }

    @Test
    fun aFailureHasAStableErrorAndNeverPasses() {
        val failed = TuneResult.Failed(TuneFailure.PLAYER, "ERROR_CODE_DECODING_FAILED", null, null)
        val r = result(outcome = failed)
        assertFalse(r.pass)
        assertEquals("failed:player:ERROR_CODE_DECODING_FAILED", r.error)
        assertEquals("—", r.transcodeReasons)
        assertEquals("session-expired", result(outcome = TuneResult.SessionExpired(null)).error)
    }
}
