// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // Scripted times and sample values; detekt skips only src/test.

package tv.stationbreak.app.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.playback.Fallback
import tv.stationbreak.core.playback.FallbackCause
import tv.stationbreak.core.playback.PlayControl
import tv.stationbreak.core.playback.Route
import tv.stationbreak.core.playback.TuneFailure
import tv.stationbreak.core.playback.TuneResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PlaybackResultFallbackTest {
    private fun result(
        route: Route,
        expected: Route?,
        fallback: Fallback? = null,
        positionError: Duration? = null,
    ): PlaybackResult {
        val resolved = BenchTestData.resolved(route, null)
        val played = TuneResult.Played(resolved, 300, 400, positionError, fallback, PlayControl.None)
        return PlaybackResult.from("video", played, Watch(0, 0, 12_000), expected, 30_000)
    }

    // ADR-0016, 4: a direct play whose seek missed is replaced by a transcode on purpose. The route check looks at the
    // route the fallback abandoned; the fallback is its own column.
    @Test
    fun aSeekMissFallbackFromTheExpectedRoutePasses() {
        val r =
            result(
                route = Route.TRANSCODE,
                expected = Route.DIRECT_PLAY,
                fallback = Fallback(Route.DIRECT_PLAY, FallbackCause.SeekMiss),
            )
        assertTrue(r.pass)
        assertEquals("seek-miss", r.fallback)
        assertNull(r.error)
        assertEquals("transcode", r.route)
        assertEquals("direct-play", r.expectedRoute)
        assertTrue(r.toJsonLine().contains("\"fallback\":\"seek-miss\""))
    }

    @Test
    fun noFallbackIsNo() {
        assertEquals("no", result(Route.DIRECT_PLAY, Route.DIRECT_PLAY).fallback)
    }

    @Test
    fun aFallbackAfterALoadFailureFailsWithItsDiagnostic() {
        val cause = FallbackCause.LoadFailed(TuneFailure.PLAYER, "ERROR_CODE_DECODER_INIT_FAILED")
        val r =
            result(route = Route.TRANSCODE, expected = Route.DIRECT_PLAY, fallback = Fallback(Route.DIRECT_PLAY, cause))
        assertFalse(r.pass)
        assertEquals("load-failed", r.fallback)
        assertEquals("fallback:load:ERROR_CODE_DECODER_INIT_FAILED", r.error)
    }

    @Test
    fun aSeekMissFallbackFromAnUnexpectedRouteFails() {
        // The resolver chose a direct stream first where the oracle said direct play: a finding, whatever played last.
        val r =
            result(
                route = Route.TRANSCODE,
                expected = Route.DIRECT_PLAY,
                fallback = Fallback(Route.DIRECT_STREAM, FallbackCause.SeekMiss),
            )
        assertFalse(r.pass)
    }

    @Test
    fun aRouteThatIsNotTheExpectedOneFailsWithoutAFallback() {
        assertFalse(result(route = Route.TRANSCODE, expected = Route.DIRECT_PLAY).pass)
        assertFalse(result(route = Route.DIRECT_STREAM, expected = Route.DIRECT_PLAY).pass)
    }

    @Test
    fun aFallbackThatMissedItsSeekStillFails() {
        val r =
            result(
                Route.TRANSCODE,
                Route.DIRECT_PLAY,
                positionError = 5.seconds,
                fallback = Fallback(Route.DIRECT_PLAY, FallbackCause.SeekMiss),
            )
        assertFalse(r.pass)
    }
}
