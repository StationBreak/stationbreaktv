// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The safety rules of the board ratings and the prefix rule of `kid-hold-13` (#987); `kid-hold-13.json` pins them. */
class BoardRatingsTest {
    private fun kidsMovie(rating: String) = movie(idOf(1), rating = rating, genres = listOf("Kids"))

    @Test
    fun everyCountryOfTheXmlHasANameToPrefixIt() {
        val named = BoardRatings.PREFIXES.values.toSet()
        for (system in AndroidTvRatingSystems.SYSTEMS) for (code in system.countries) assertTrue(code in named, code)
    }

    @Test
    fun aCountryNamePrefixReadsAsItsCode() {
        for ((name, code) in BoardRatings.PREFIXES) {
            if (code == BoardRatings.FSK || code == "US") continue
            for (separator in listOf("-", ":", "/", " : ")) {
                assertEquals(
                    RatingMap.levelOf("$code-12"),
                    RatingMap.levelOf("$name${separator}12"),
                    "$name$separator" + "12",
                )
            }
        }
    }

    @Test
    fun aBareAllAgesWordIsFamilyAtMostAndNeverKids() {
        for (word in listOf("U", "ALL", "GA", "ATP", "UNIVERSAL", "u", "all", "Rated U")) {
            assertEquals(2, RatingMap.levelOf(word), word)
            assertEquals(RatingTier.Family, RatingTier.of(kidsMovie(word), null), word)
        }
        // With a system in them they keep their level.
        for (rating in listOf("GB-U", "UK:U", "KR-ALL", "BR-GA", "AR-ATP")) {
            assertEquals(0, RatingMap.levelOf(rating), rating)
            assertEquals(RatingTier.Kids, RatingTier.of(kidsMovie(rating), null), rating)
        }
    }

    @Test
    fun fskReadsItsFiveOfficialValuesOnly() {
        assertEquals(listOf(0, 1, 3, 5, 6), listOf("0", "6", "12", "16", "18").map { RatingMap.levelOf("FSK-$it") })
        for (rating in listOf("FSK-7", "FSK-9", "FSK-5", "FSK", "FSK-", "ab", "ab 7", "DE:FSK 7", "FSK-12PG")) {
            assertNull(RatingMap.levelOf(rating), rating)
        }
    }

    @Test
    fun theTinyMediaManagerGermanSpellingsAllReadSixteen() {
        for (rating in listOf("DE:FSK 16", "DE:FSK16", "DE:16", "DE:ab 16", "Germany:FSK 16", "FSK 16", "fsk16")) {
            assertEquals(5, RatingMap.levelOf(rating), rating)
            assertEquals(RatingTier.Adult, RatingTier.of(movie(idOf(1), rating = rating), null), rating)
        }
    }

    @Test
    fun aBoardRatingRoundsUpNeverDown() {
        val expected =
            mapOf(
                "12A" to 3,
                "GB-12" to 3,
                "FSK 12" to 3,
                "PG12" to 3,
                "AU-M" to 5,
                "GB-15" to 5,
                "MA15+" to 5,
                "AU-PG" to 2,
            )
        for ((rating, level) in expected) assertEquals(level, RatingMap.levelOf(rating), rating)
    }

    @Test
    fun theUsPrefixReadsTheUsLadderAndTv14IsNeverTuvalu() {
        assertEquals(RatingMap.levelOf("PG"), RatingMap.levelOf("US:PG"))
        assertEquals(RatingMap.levelOf("R"), RatingMap.levelOf("US-R"))
        assertEquals(4, RatingMap.levelOf("TV-14"))
        assertEquals(4, RatingMap.levelOf("US:TV-14"))
        assertNull(RatingMap.levelOf("US:"))
        assertNull(RatingMap.levelOf("US-12PG"))
    }

    @Test
    fun anUnknownBoardRatingStaysAdult() {
        for (rating in listOf("GB-12PG", "JP-PG15", "AU-MA16+", "UK:", "UK", "United Kingdom:", "Germany:Zero")) {
            assertNull(RatingMap.levelOf(rating), rating)
            assertEquals(RatingTier.Adult, RatingTier.of(kidsMovie(rating), null), rating)
        }
    }
}
