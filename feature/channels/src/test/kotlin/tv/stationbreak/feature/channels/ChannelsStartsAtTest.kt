// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.ui.ProgramText
import java.time.ZoneId
import kotlin.time.Instant

class ChannelsStartsAtTest {
    // A day start of 05:30 UTC (not the usual 06:00): the time shown follows the channel's own start.
    private val dayStart = 1_800_000_000_000L - 1_800_000_000_000L % 86_400_000L + 5 * 3_600_000L + 30 * 60_000L
    private val reasons = mapOf(A to HiddenReason.EmptyToday)

    private fun starting(onSinceMs: Long) = summary(A, 5).copy(onSince = Instant.fromEpochMilliseconds(onSinceMs))

    @Test
    fun aChannelStartingLaterCarriesItsDayStart() {
        val row = channelRows(listOf(starting(dayStart)), reasons, nowMs = dayStart - 1).single()
        assertEquals(dayStart, row.startsAtMs)
        assertEquals("5:30 AM", ProgramText.time(row.startsAtMs!!, ZoneId.of("UTC"), is24h = false))
    }

    @Test
    fun onceItHasStartedItIsPlainEmptyToday() {
        assertNull(channelRows(listOf(starting(dayStart)), reasons, nowMs = dayStart).single().startsAtMs)
    }

    @Test
    fun otherReasonsNeverStartLater() {
        val other = mapOf(A to HiddenReason.NoMatches)
        assertNull(channelRows(listOf(starting(dayStart)), other, nowMs = 0L).single().startsAtMs)
    }
}
