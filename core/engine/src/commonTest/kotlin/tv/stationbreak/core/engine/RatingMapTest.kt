// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.rules.RatingLadder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The rating map (ADR-0028, #987). The full table of cases is in `kid-hold-6.json` and `kid-hold-10.json`. */
class RatingMapTest {
    // TV and PG are also ISO country codes (Tuvalu, Papua New Guinea): the US names are read first, whole.
    @Test
    fun tv14IsNeverReadAsACountryCode() {
        assertEquals(4, RatingMap.levelOf("TV-14"))
        assertEquals(4, RatingMap.levelOf("tv-14-v"))
        assertEquals(6, RatingMap.levelOf("TV-14/TV-MA"))
        assertEquals(3, RatingMap.levelOf("PG-13"))
    }

    // Pins the parse order (#1006): a system prefix (countryLevel, cut at the first `-`, `:` or `/`) is tried before a
    // `/` list (listLevel). Today no country table has a `TV` or `PG` code, so these fall through to the list. FUTURE
    // HAZARD: a PG or TV country table would cut `PG-13/R` and `TV-14/TV-MA` at the first `-` and misread them, so
    // whoever adds one must see this fail and fix the order first.
    @Test
    fun aListLikeUsNamesIsReadAsAListNotAsACountryPrefix() {
        assertNull(CountryRatingTables.countryLevel("PG-13/R"))
        assertNull(CountryRatingTables.countryLevel("TV-14/TV-MA"))
        assertEquals(5, RatingMap.levelOf("PG-13/R"))
        assertEquals(6, RatingMap.levelOf("TV-14/TV-MA"))
    }

    // kid-hold-10 (#987): AU PG is 10 (the override, AndroidTvRatingsTest). A space is not a country separator, so
    // `AU PG` stays unknown, so adult.
    @Test
    fun auPgIsTenWithACountrySeparator() {
        assertNull(RatingMap.levelOf("AU PG"))
        assertEquals(2, RatingMap.levelOf("AU-PG"))
        assertEquals(2, RatingMap.levelOf("AU:PG"))
    }

    @Test
    fun aUsLadderNameMapsToItsRulesLadderRankExceptApproved() {
        for (name in RatingLadder.names - "Approved") {
            assertEquals(RatingLadder.rankOf(name), RatingMap.levelOf(name), name)
        }
        assertNull(RatingMap.levelOf("Approved"))
        assertEquals(RatingLadder.tiers.size, RatingMap.LEVELS.size)
    }
}
