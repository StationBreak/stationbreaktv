// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #1123: [CeilingOracle] reads a country rating on its own, and fails closed on a string it does not read. */
class CeilingOracleTest {
    // The ladder's ranks by name: ALL is the lowest, then 7, PG (10), 13, 14, 16, 18.
    private val all = 0
    private val seven = 1
    private val family = 2
    private val thirteen = 3
    private val fourteen = 4
    private val sixteen = 5
    private val eighteen = 6

    @Test
    fun readsCountryAgesAndAllAgesWordsOnTheLadder() {
        val expected =
            mapOf(
                "DE-6" to seven,
                "DE-12" to thirteen,
                "de:16" to sixteen,
                "DE-18" to eighteen,
                "DE-ALL" to all,
                "All Ages" to all,
                "TV-14" to fourteen,
            )
        for ((raw, rank) in expected) assertEquals(rank, CeilingOracle.rankOf(raw), raw)
    }

    // #1124: the spellings the pool admits under a ceiling that are not a country age, read from the oracle's own list.
    @Test
    fun readsTheOtherSpellingsThePoolAdmits() {
        val expected =
            mapOf(
                "AU-PG" to family,
                "GP" to family,
                "PG13" to thirteen,
                "SG-PG13" to thirteen,
                "NZ-PGR" to family,
                "Rated PG" to family,
                "SAM 13" to thirteen,
                "C8" to family,
                "M" to sixteen,
                "A" to eighteen,
            )
        for ((raw, rank) in expected) assertEquals(rank, CeilingOracle.rankOf(raw), raw)
    }

    @Test
    fun aBareAgeIsFamilyAtLeast() {
        for ((raw, rank) in mapOf("6" to family, "12+" to thirteen)) assertEquals(rank, CeilingOracle.rankOf(raw), raw)
    }

    @Test
    fun anUnknownStringHasNoRank() {
        val unknown =
            listOf("ZZ-invented", "DE-", "DE-six", "", "  ", "12 12", "DE-6/DE-12", "AU PG", "US-PG", "Rated", "FSK 16")
        for (raw in unknown) assertNull(CeilingOracle.rankOf(raw), raw)
        assertNull(CeilingOracle.rankOf(null))
    }
}
