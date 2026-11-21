// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Days since 1970-01-01 of a civil date (Howard Hinnant's days-from-civil), written out here so the test does not lean
// on the code it checks.
private fun epochDay(
    year: Int,
    month: Int,
    day: Int,
): Long {
    val y = if (month <= 2) year - 1 else year
    val era = y.floorDiv(400)
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468
}

/** When the broadcast day of the civil date starts: 06:00 local, at a standard [offset] minutes from UTC. */
internal fun dayStartOf(
    year: Int,
    month: Int,
    day: Int,
    offset: Int = 0,
): Long = epochDay(year, month, day) * DAY_MS + DAY_START_MS - offset * 60_000L

class SeasonTest {
    private fun on(
        season: Season,
        year: Int,
        month: Int,
        day: Int,
        offset: Int = 0,
    ) = season.contains(dayStartOf(year, month, day, offset), offset)

    @Test
    fun theCivilDateIsRightAcrossLeapYearsAndTheEpoch() {
        assertEquals(1 to 1, monthAndDayOf(0))
        assertEquals(2 to 29, monthAndDayOf(epochDay(2028, 2, 29)))
        assertEquals(3 to 1, monthAndDayOf(epochDay(2028, 2, 29) + 1))
        assertEquals(3 to 1, monthAndDayOf(epochDay(2027, 2, 28) + 1))
        assertEquals(12 to 31, monthAndDayOf(epochDay(1999, 12, 31)))
        assertEquals(1 to 1, monthAndDayOf(epochDay(2000, 12, 31) + 1))
        assertEquals(2 to 29, monthAndDayOf(epochDay(2000, 2, 29)))
        assertEquals(1 to 5, monthAndDayOf(epochDay(1969, 1, 5)))
    }

    @Test
    fun christmasRunsFromNovember22ThroughDecember31() {
        assertFalse(on(Season.Christmas, 2026, 11, 21))
        assertTrue(on(Season.Christmas, 2026, 11, 22))
        assertTrue(on(Season.Christmas, 2026, 12, 25))
        assertTrue(on(Season.Christmas, 2026, 12, 31))
        // The year roll: January 1 is out, and so is the rest of the year.
        assertFalse(on(Season.Christmas, 2027, 1, 1))
        assertFalse(on(Season.Christmas, 2027, 7, 15))
        assertTrue(on(Season.Christmas, 2027, 11, 22))
    }

    @Test
    fun halloweenRunsFromSeptember24ThroughOctober31() {
        assertFalse(on(Season.Halloween, 2026, 9, 23))
        assertTrue(on(Season.Halloween, 2026, 9, 24))
        assertTrue(on(Season.Halloween, 2026, 10, 31))
        assertFalse(on(Season.Halloween, 2026, 11, 1))
        assertFalse(on(Season.Halloween, 2026, 12, 25))
    }

    @Test
    fun aLeapDayIsNotSpecialAndTheYearsAgree() {
        for (season in Season.entries) {
            assertFalse(on(season, 2028, 2, 29), "$season on a leap day")
            assertFalse(on(season, 2027, 2, 28), "$season on February 28")
            assertEquals(
                on(season, 2028, 12, 31),
                on(season, 2027, 12, 31),
                "$season: a leap year and a common year agree at the year end",
            )
            assertEquals(on(season, 2028, 10, 31), on(season, 2027, 10, 31))
        }
    }

    @Test
    fun theViewersCalendarDecidesNotUtc() {
        // Day starts are 06:00 local, so at a -10 h standard offset the instant is 16:00 UTC of the same date; at +13 h
        // it is 17:00 UTC of the day before. The date is the viewer's either way.
        for (offset in listOf(-600, -300, 0, 330, 780)) {
            assertFalse(on(Season.Christmas, 2026, 11, 21, offset), "Nov 21 at $offset")
            assertTrue(on(Season.Christmas, 2026, 11, 22, offset), "Nov 22 at $offset")
            assertTrue(on(Season.Christmas, 2026, 12, 31, offset), "Dec 31 at $offset")
            assertFalse(on(Season.Christmas, 2027, 1, 1, offset), "Jan 1 at $offset")
        }
    }

    @Test
    fun aWindowCannotWrapTheYearEnd() {
        assertFailsWith<IllegalArgumentException> { SeasonWindow(12, 20, 1, 5) }
        assertFailsWith<IllegalArgumentException> { SeasonWindow(13, 1, 13, 2) }
        assertFailsWith<IllegalArgumentException> { SeasonWindow(1, 0, 1, 2) }
        assertFailsWith<IllegalArgumentException> { SeasonWindow(2, 30, 3, 2) }
        assertFailsWith<IllegalArgumentException> { SeasonWindow(4, 1, 4, 31) }
        SeasonWindow(2, 29, 3, 1)
    }

    @Test
    fun theFourSeasonalRecipesCarryTheirWindows() {
        fun season(key: String) = StarterCatalog.recipe(key)?.season
        assertEquals(Season.Christmas, season("snowglobe"))
        assertEquals(Season.Christmas, season("always-december"))
        assertEquals(Season.Halloween, season("spooky-sleepover"))
        assertEquals(Season.Halloween, season("jack-o-lantern-lounge"))
        assertEquals(
            setOf(900, 910),
            StarterCatalog.recipes
                .filter { it.band == Band.Seasonal }
                .map { it.number }
                .toSet(),
        )
        assertNull(StarterCatalog.recipe("prestige")?.season)
        // Snowglobe and Spooky Sleepover stay where they were, and keep their ceilings.
        assertEquals(20 to "G", StarterCatalog.recipe("snowglobe")!!.let { it.number to it.maxRating })
        assertEquals(45 to "PG", StarterCatalog.recipe("spooky-sleepover")!!.let { it.number to it.maxRating })
    }

    @Test
    fun aSeasonalBandRecipeNeedsASeason() {
        assertFailsWith<IllegalArgumentException> { recipeOf("loose", 905) }
        recipeOf("tied", 905, season = Season.Halloween)
    }
}
