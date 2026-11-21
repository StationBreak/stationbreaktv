// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.rules.RatingLadder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The US-first rating map (ADR-0028, #987). The full table of cases is in `kid-hold-6.json`. */
class RatingMapTest {
    // TV and PG are also ISO country codes (Tuvalu, Papua New Guinea): the US names are read first, whole.
    @Test
    fun tv14IsNeverReadAsACountryCode() {
        assertEquals(4, RatingMap.levelOf("TV-14"))
        assertEquals(4, RatingMap.levelOf("tv-14-v"))
        assertEquals(6, RatingMap.levelOf("TV-14/TV-MA"))
        assertEquals(3, RatingMap.levelOf("PG-13"))
    }

    // #987 maps AU PG to 10 once a licensed Australian table lands. Until then it is unknown, so adult.
    @Test
    fun auPgIsUnknownUntilALicensedTableLands() {
        assertNull(RatingMap.levelOf("AU PG"))
        assertNull(RatingMap.levelOf("AU-PG"))
        assertNull(RatingMap.levelOf("AU:PG"))
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
