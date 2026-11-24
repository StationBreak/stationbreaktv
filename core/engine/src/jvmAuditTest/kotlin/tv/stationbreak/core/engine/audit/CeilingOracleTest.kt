// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #1123: [CeilingOracle] reads a country rating on its own, and fails closed on a string it does not read. */
class CeilingOracleTest {
    @Test
    fun readsCountryAgesAndAllAgesWordsOnTheLadder() {
        assertEquals(1, CeilingOracle.rankOf("DE-6"))
        assertEquals(3, CeilingOracle.rankOf("DE-12"))
        assertEquals(5, CeilingOracle.rankOf("de:16"))
        assertEquals(6, CeilingOracle.rankOf("DE-18"))
        assertEquals(0, CeilingOracle.rankOf("DE-ALL"))
        assertEquals(0, CeilingOracle.rankOf("All Ages"))
        assertEquals(4, CeilingOracle.rankOf("TV-14"))
    }

    @Test
    fun aBareAgeIsFamilyAtLeast() {
        assertEquals(2, CeilingOracle.rankOf("6"))
        assertEquals(3, CeilingOracle.rankOf("12+"))
    }

    @Test
    fun anUnknownStringHasNoRank() {
        for (raw in listOf("ZZ-invented", "DE-", "DE-six", "", "  ", "12 12", "DE-6/DE-12")) assertNull(CeilingOracle.rankOf(raw), raw)
        assertNull(CeilingOracle.rankOf(null))
    }
}
