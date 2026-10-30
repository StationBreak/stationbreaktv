// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which SeekDone is which seek's (spec 20, ADR-0021 9): one per Seeking; a seek pending folds the next one in. */
class SeekTrackerTest {
    private val tracker = SeekTracker(slackMs = 2_000)

    @Test
    fun nothingInFlightTakesNoSeekDone() {
        assertNull(tracker.onSeekDone(1_000))
        assertFalse(tracker.inFlight)
    }

    @Test
    fun aSeekDoneAfterItsSeekingIsThatSeeksWhereverItLands() {
        tracker.sent(10_000, live = false)
        tracker.onSeeking(10_000)
        assertEquals(SeekTracker.Landing(50_000, 50_000, report = true, live = false), tracker.onSeekDone(50_000))
        assertFalse(tracker.inFlight)
    }

    @Test
    fun withNoSeekingSeenOnlyALandingWithinTheSlackCounts() {
        tracker.sent(10_000, live = false)
        // An earlier seek's SeekDone, still buffered: far from the target, dropped.
        assertNull(tracker.onSeekDone(12_001))
        assertTrue(tracker.inFlight)
        // Folded into the seek pending: lands within the slack.
        assertEquals(12_000L, tracker.onSeekDone(12_000)?.atMs)
    }

    @Test
    fun anUnknownPositionCountsOnlyAfterTheSeeksOwnSeeking() {
        tracker.sent(10_000, live = false)
        assertNull(tracker.onSeekDone(null))
        tracker.onSeeking(10_000)
        assertEquals(SeekTracker.Landing(10_000, null, report = true, live = false), tracker.onSeekDone(null))
    }

    @Test
    fun aSeekingForAnotherTargetIsNotThisSeeks() {
        tracker.sent(10_000, live = false)
        tracker.onSeeking(4_000)
        assertNull(tracker.onSeekDone(40_000))
    }

    @Test
    fun onlyTheLiveSeekLandsLive() {
        tracker.sent(10_000, live = false)
        tracker.onSeeking(10_000)
        tracker.sent(60_000, live = true)
        // The step seek's SeekDone came after the live seek went out: far from the live target, dropped.
        assertNull(tracker.onSeekDone(10_000))
        tracker.onSeeking(60_000)
        assertTrue(checkNotNull(tracker.onSeekDone(60_000)).live)
    }

    @Test
    fun aLaterStepSeekOrACancelIsNotLive() {
        tracker.sent(60_000, live = true)
        tracker.sent(50_000, live = false)
        tracker.onSeeking(50_000)
        assertFalse(checkNotNull(tracker.onSeekDone(50_000)).live)

        tracker.sent(60_000, live = true)
        tracker.cancelLive()
        tracker.onSeeking(60_000)
        assertFalse(checkNotNull(tracker.onSeekDone(60_000)).live)
    }

    @Test
    fun closingReportsAnOpenSeekOnceAndItsSeekDoneIsNotReportedAgain() {
        tracker.sent(10_000, live = true)
        assertEquals(9_000L, tracker.close(9_000))
        assertNull(tracker.close(9_500))
        tracker.onSeeking(10_000)
        val landing = checkNotNull(tracker.onSeekDone(10_000))
        assertFalse(landing.report)
        assertTrue(landing.live)
    }

    @Test
    fun playingClosesASeekOnlyOnceItsOwnSeekingWasSeen() {
        tracker.sent(10_000, live = false)
        // Playing before the seek even began (a resume asked for just after it): the seek is still open.
        assertNull(tracker.closeOnPlaying(3_000))
        tracker.onSeeking(10_000)
        assertEquals(10_100L, tracker.closeOnPlaying(10_100))
    }
}
