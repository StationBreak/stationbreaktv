// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import tv.stationbreak.core.model.schedule.SchedulerSettings
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Spec 09 task 3.2 (Vimes L4): the retention is a named app-config default, and must stay longer than the longest
 * epoch plus the largest grace window, which it reads from the format's own constants.
 */
class HistoryRetentionTest {
    @Test
    fun theDefaultIsThirtyDays() {
        assertEquals(30, HistoryRetention.DEFAULT_DAYS)
        assertEquals(30, HistoryRetention().days)
        assertEquals(30.days.inWholeMilliseconds, HistoryRetention().millis)
    }

    @Test
    fun threeDaysIsRefusedAndThirtyIsAccepted() {
        assertThrows(IllegalArgumentException::class.java) { HistoryRetention(days = 3) }
        assertEquals(30, HistoryRetention(days = 30).days)
    }

    @Test
    fun theLimitIsTheLongestEpochPlusTheLargestGrace() {
        val longest = SchedulerSettings.MAX_EPOCH_MS + SchedulerSettings.MAX_GRACE_MS
        assertEquals(72.hours.inWholeMilliseconds, longest)
        assertEquals(longest, HistoryRetention.SAFE_LOOKBACK_MS)
        // Whole days: 3 days equals the limit and is refused; 4 days is the smallest accepted.
        assertThrows(IllegalArgumentException::class.java) { HistoryRetention(days = 3) }
        assertEquals(4, HistoryRetention(days = 4).days)
    }

    @Test
    fun zeroAndNegativeAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { HistoryRetention(days = 0) }
        assertThrows(IllegalArgumentException::class.java) { HistoryRetention(days = -30) }
    }
}
