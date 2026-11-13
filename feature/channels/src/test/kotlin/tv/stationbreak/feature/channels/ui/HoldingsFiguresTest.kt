// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelHoldings
import tv.stationbreak.core.domain.lineup.HeldTitle
import tv.stationbreak.core.model.CanonicalId

private const val HOUR_MS = 3_600_000L

/** The properties sheet draws a channel's holdings through the same figures as the guide's channel box (#757). */
class HoldingsFiguresTest {
    private fun title(
        n: Int,
        name: String,
    ) = HeldTitle(CanonicalId.parse(n.toString(16).padStart(32, '0')), name, HOUR_MS, anchor = false)

    @Test
    fun aChannelWithHomesListsThemAndCountsTheRest() {
        val homes = (1..6).map { title(it, "Home $it") }

        val figures = ChannelHoldings(412 * HOUR_MS, 37, 37, homes, listOf(title(9, "Not this"))).figures()

        assertEquals(412L, figures.hours)
        assertEquals(17L, figures.days)
        assertEquals(37, figures.titles)
        assertTrue(figures.homeTo)
        assertEquals((1..6).map { "Home $it" }, figures.names)
        assertEquals(31, figures.more)
    }

    @Test
    fun aChannelWithNoHomesIsMostlyItsBiggestTitles() {
        val top = (1..3).map { title(it, "Big $it") }

        val figures = ChannelHoldings(40 * HOUR_MS, 3, 0, emptyList(), top).figures()

        assertFalse(figures.homeTo)
        assertEquals(listOf("Big 1", "Big 2", "Big 3"), figures.names)
        assertNull(figures.days)
        assertEquals(0, figures.more)
    }
}
