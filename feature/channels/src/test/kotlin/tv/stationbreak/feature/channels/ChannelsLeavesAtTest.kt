// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelStatus
import tv.stationbreak.core.domain.lineup.ShelfReason
import tv.stationbreak.core.ui.ProgramText
import java.time.ZoneId
import kotlin.time.Instant

class ChannelsLeavesAtTest {
    private val leaveMs = 1_800_000_000_000L - 1_800_000_000_000L % 86_400_000L + 6 * 3_600_000L

    private val at = Instant.fromEpochMilliseconds(leaveMs)

    private fun leaving(reason: ShelfReason) = summary(A, 5, shelfReason = reason).copy(leavesAt = at)

    @Test
    fun anOnRowThatLeavesCarriesTheTimeInsteadOfTheShelfReason() {
        for (reason in listOf(ShelfReason.BelowCap, ShelfReason.Empty)) {
            val row = channelRows(listOf(leaving(reason)), emptyMap()).single()
            assertEquals(ChannelGroup.On, row.group)
            assertEquals(leaveMs, row.leavesAtMs)
            assertNull(row.shelfReason)
        }
        assertEquals("6:00 AM", ProgramText.time(leaveMs, ZoneId.of("UTC"), is24h = false))
        assertEquals("06:00", ProgramText.time(leaveMs, ZoneId.of("UTC"), is24h = true))
    }

    @Test
    fun shelfAndPlainOnRowsAreUnchanged() {
        val shelf = summary(A, 5, ChannelStatus.Shelf, shelfReason = ShelfReason.BelowCap)
        val shelfRow = channelRows(listOf(shelf), emptyMap()).single()
        assertNull(shelfRow.leavesAtMs)
        assertEquals(ShelfReason.BelowCap, shelfRow.shelfReason)
        assertNull(channelRows(listOf(summary(A, 5)), emptyMap()).single().leavesAtMs)
    }
}
