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
    private val explicit = 7

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

    // #987: the boards' labels, read from the oracle's own list, with the prefixes the engine reads.
    @Test
    fun readsTheBoardRatingsAfterAPrefix() {
        val expected =
            mapOf(
                "GB-U" to all,
                "UK:U" to all,
                "United Kingdom:12A" to thirteen,
                "12A" to thirteen,
                "GB-15" to sixteen,
                "FSK-0" to all,
                "FSK-12" to thirteen,
                "FSK 16" to sixteen,
                "ab 6" to seven,
                "DE:FSK 16" to sixteen,
                "Germany:FSK 18" to eighteen,
                "Keine Jugendfreigabe" to eighteen,
                "JP-G" to all,
                "Japan:PG12" to thirteen,
                "R 15+" to sixteen,
                "AU-M" to sixteen,
                "MA 15+" to sixteen,
                "Australia:MA15+" to sixteen,
                "AU-R" to explicit,
                "Australia:X18+" to explicit,
                "Singapore:R21" to explicit,
                "GB-R18" to explicit,
                "US:PG" to family,
                "US-R" to sixteen,
            )
        for ((raw, rank) in expected) assertEquals(rank, CeilingOracle.rankOf(raw), raw)
    }

    @Test
    fun aBareAllAgesWordIsFamilyAtMost() {
        for (word in listOf("U", "ALL", "GA", "ATP", "UNIVERSAL")) {
            assertEquals(family, CeilingOracle.rankOf(word), word)
        }
    }

    @Test
    fun aBareAgeIsFamilyAtLeast() {
        for ((raw, rank) in mapOf("6" to family, "12+" to thirteen)) assertEquals(rank, CeilingOracle.rankOf(raw), raw)
    }

    @Test
    fun anUnknownStringHasNoRank() {
        val unknown =
            listOf(
                "ZZ-invented",
                "DE-",
                "DE-six",
                "",
                "  ",
                "12 12",
                "DE-6/DE-12",
                "AU PG",
                "Rated",
                "FSK-7",
                "UK:",
                "GB-12PG",
                "JP-PG15",
                "AU-MA16+",
                "ab",
                "FSK",
            )
        for (raw in unknown) assertNull(CeilingOracle.rankOf(raw), raw)
        assertNull(CeilingOracle.rankOf(null))
    }
}
