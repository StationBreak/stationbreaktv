// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Catalog 12's date gating (#592): a seasonal channel is a candidate only on the days of its season, as any channel is
 * only one with something that fits. It goes to the shelf out of season (reason Empty), comes back by itself, and a
 * viewer's number survives the months it is off.
 */
class LineupPlannerSeasonTest {
    private val fillers = listOf("alpha" to 110, "bravo" to 120, "charlie" to 210, "delta" to 310, "echo" to 410)
    private val holiday = recipeOf("tinsel", 900, season = Season.Christmas)
    private val spooky = recipeOf("cobweb", 910, season = Season.Halloween)
    private val catalog = TestCatalog(fillers.map { (k, n) -> recipeOf(k, n) } + holiday + spooky)

    // Everything fills, in or out of season: only the date decides the seasonal two.
    private val library = libraryOf(catalog.recipes.associate { it.key to QUALIFYING_ITEMS })

    private fun plan(
        current: Lineup?,
        year: Int,
        month: Int,
        day: Int,
        offset: Int = 0,
    ): Lineup {
        val boundary = dayStartOf(year, month, day, offset)
        return planOf(current, library, catalog, nowMs = boundary - DAY_MS / 2, offset = offset, boundaryMs = boundary)
    }

    private fun Lineup.isOn(key: String) = channel(key).state == ChannelState.On

    @Test
    fun outOfSeasonTheSeasonalChannelsAreOnTheShelfEmptyAndInSeasonTheyAreOn() {
        val july = plan(null, 2027, 7, 15)
        assertTrue(july.finalised)
        for (key in listOf("tinsel", "cobweb")) {
            assertEquals(ChannelState.Shelf, july.channel(key).state, key)
            assertEquals(ShelfReason.Empty, july.channel(key).shelfReason, key)
        }
        assertTrue(fillers.all { july.isOn(it.first) })

        // The first choice in December has Christmas but not Halloween.
        val december = plan(null, 2027, 12, 10)
        assertTrue(december.isOn("tinsel"))
        assertEquals(ShelfReason.Empty, december.channel("cobweb").shelfReason)
        assertTrue(plan(null, 2027, 10, 10).isOn("cobweb"))
    }

    @Test
    fun aChannelComesInAtItsFirstDayAndLeavesAfterItsLast() {
        var lineup = plan(null, 2027, 11, 20)
        assertFalse(lineup.isOn("tinsel"))
        // The plan for the day before the window opens: still off.
        lineup = plan(lineup, 2027, 11, 21)
        assertFalse(lineup.isOn("tinsel"))
        // The plan whose day is November 22: on from that day's start, not before.
        val boundary = dayStartOf(2027, 11, 22)
        lineup = plan(lineup, 2027, 11, 22)
        val tinsel = lineup.channel("tinsel")
        assertEquals(ChannelState.On, tinsel.state)
        assertEquals(boundary, tinsel.onSinceMs)
        // December 31 is the last day: the plan for it keeps it; the plan for January 1 sends it off at that day's
        // start.
        lineup = plan(lineup, 2027, 12, 31)
        assertTrue(lineup.isOn("tinsel"))
        assertNull(lineup.channel("tinsel").leavesAtMs)
        val january = dayStartOf(2028, 1, 1)
        lineup = plan(lineup, 2028, 1, 1)
        assertEquals(january, lineup.channel("tinsel").leavesAtMs)
        assertEquals(ShelfReason.Empty, lineup.channel("tinsel").shelfReason)
        // The roll takes it to the shelf at that day's start.
        assertEquals(ChannelState.Shelf, lineup.rolled(january).channel("tinsel").state)
    }

    @Test
    fun theSameDayGivesTheSameLineupOnEveryTv() {
        val a = plan(null, 2027, 12, 10)
        val b = plan(null, 2027, 12, 10)
        assertEquals(a, b)
        // A leap year's February is out for both, and the year roll behaves the same in a leap year.
        assertFalse(plan(a, 2028, 2, 29).rolled(dayStartOf(2028, 2, 29)).isOn("tinsel"))
        assertFalse(plan(plan(null, 2028, 12, 31), 2029, 1, 1).rolled(dayStartOf(2029, 1, 1)).isOn("tinsel"))
    }

    @Test
    fun aViewersNumberOnASeasonalChannelSurvivesTheOffMonths() {
        var lineup = plan(null, 2027, 12, 10)
        lineup = lineup.withChannel("tinsel") { it.copy(numberOverride = 640) }
        lineup = plan(lineup, 2028, 1, 5).rolled(dayStartOf(2028, 1, 5))
        assertEquals(ChannelState.Shelf, lineup.channel("tinsel").state)
        assertEquals(640, lineup.channel("tinsel").number)
        lineup = plan(lineup, 2028, 7, 4)
        assertEquals(640, lineup.channel("tinsel").number)
        lineup = plan(lineup, 2028, 12, 2)
        assertEquals(ChannelState.On, lineup.channel("tinsel").state)
        assertEquals(640, lineup.channel("tinsel").number)
    }

    @Test
    fun theLeftoversAreUntouchedBySeasonsAndNoOtherChannelIsGated() {
        val july = plan(null, 2027, 7, 15)
        val december = plan(null, 2027, 12, 10)
        for (key in fillers.map { it.first } + "kids-odds" + "odds") {
            assertEquals(july.isOn(key), december.isOn(key), key)
        }
    }

    @Test
    fun theViewersCalendarDecidesTheDayAtTheLineupsOffset() {
        // At -300 the boundary of November 22 is 11:00 UTC: November 22 locally, so the channel is in.
        val inSeason = plan(null, 2027, 11, 22, offset = -300)
        assertTrue(inSeason.isOn("tinsel"))
        assertFalse(plan(null, 2027, 11, 21, offset = -300).isOn("tinsel"))
        assertEquals(-300, inSeason.utcOffsetMinutes)
    }
}
