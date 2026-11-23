// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.rules.RatingLadder
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The safety rules of the non-US ratings of `kid-hold-10` (#987): the Android TV ratings XML's systems
 * ([AndroidTvRatingSystems]) map only at or above their age hint, unknown and unrated stay adult, and a bare age is
 * never kids. The full table is pinned by `kid-hold-10.json`, written from the XML by an independent reader.
 */
class AndroidTvRatingsTest {
    // The ladder rung of an age, from the ladder of #987 (ALL is all ages, 7 is 1 to 7, 10 is 8 to 10, 13 is 11 to 13,
    // 14, 16 is 15 to 17, 18 is 18 and up). Written here on its own, not with RatingMap's code.
    private fun rungOf(age: Int): Int =
        when {
            age <= 0 -> 0
            age <= 7 -> 1
            age <= 10 -> 2
            age <= 13 -> 3
            age == 14 -> 4
            age <= 17 -> 5
            else -> 6
        }

    // The StationBreak decisions on top of the XML (CountryRatingTables' overrides): the AU PG override is the one
    // rating below its hint (owner decision, #987); the others fail up from a hint the rating's own name contradicts.
    private val overrides: Map<String, Int?> =
        mapOf(
            "AU-PG" to 2,
            "NZ-PGR" to 2,
            "NZ-AO" to 7,
            "SG-PG" to 2,
            "ES-X" to 7,
            "ES-X-RATED" to 7,
            "CA-E" to null,
            "CA-EXEMPT" to null,
        )

    private fun eachRating(check: (key: String, ageHint: Int) -> Unit) {
        var count = 0
        for (system in AndroidTvRatingSystems.SYSTEMS) {
            for (country in system.countries) {
                for (rating in system.ratings) {
                    for (name in rating.names) {
                        count++
                        check("$country-$name", rating.ageHint)
                    }
                }
            }
        }
        assertTrue(count > 500, "ratings checked: $count")
    }

    private fun kidsMovie(
        rating: String?,
        id: Int = 1,
    ) = movie(idOf(id), rating = rating, genres = listOf("Kids"))

    @Test
    fun everyRatingMapsAtOrAboveItsAgeHint() {
        eachRating { key, hint ->
            val level = RatingMap.levelOf(key)
            if (key in overrides) {
                assertEquals(overrides[key], level, key)
            } else {
                assertEquals(rungOf(hint), level, "$key (hint $hint)")
            }
        }
    }

    @Test
    fun everyCountryPrefixSpellingReadsTheSame() {
        eachRating { key, _ ->
            val level = RatingMap.levelOf(key)
            val country = key.substringBefore('-')
            val name = key.substringAfter('-')
            assertEquals(level, RatingMap.levelOf("$country:$name"), "$country:$name")
            assertEquals(level, RatingMap.levelOf("$country: $name"), "$country: $name")
            assertEquals(level, RatingMap.levelOf("$country/$name"), "$country/$name")
            assertEquals(level, RatingMap.levelOf(key.lowercase()), key.lowercase())
        }
    }

    @Test
    fun nothingTheTableMapsBelow18IsExplicit() {
        eachRating { key, _ ->
            val level = RatingMap.levelOf(key)
            if (level != null && level < 6) assertFalse(RatingMap.isExplicit(key), key)
            if (level == 7) assertTrue(RatingMap.isExplicit(key), key)
        }
    }

    // A kid channel admits a rating only at level ALL or 7: never one whose age hint is above 7 (AU PG is 10).
    @Test
    fun aKidChannelNeverAdmitsARatingAboveItsKidsLimit() {
        eachRating { key, hint ->
            val film = kidsMovie(key)
            val admitted = KidHold.admits(film, null)
            if (hint > 7 || key == "AU-PG") assertFalse(admitted, key)
            for (ceiling in listOf(null) + KidHold.CEILING_CHOICES) {
                if (KidHold.passesKidCeiling(film, null, ceiling)) assertTrue(hint <= 7 && key != "AU-PG", key)
            }
        }
    }

    @Test
    fun theAustralianPgOverrideIsTen() {
        for (spelling in listOf("AU-PG", "AU:PG", "au-pg", "AU/PG", "AU - PG")) {
            assertEquals(2, RatingMap.levelOf(spelling), spelling)
        }
        assertEquals(RatingTier.Family, RatingTier.of(kidsMovie("AU-PG"), null))
        // The XML has AU PG at 15 and AU M at 15: the override touches PG only.
        assertEquals(5, RatingMap.levelOf("AU-M"))
    }

    // #1006, owner decision 2026-11-23: a bare age has no system, so it caps at Family and is never kids.
    @Test
    fun aBareAgeIsFamilyCappedNotKids() {
        for (age in listOf("0", "6", "6+", "7", "7+", "10")) {
            assertEquals(2, RatingMap.levelOf(age), age)
            assertEquals(RatingTier.Family, RatingTier.of(kidsMovie(age), null), age)
            assertFalse(KidHold.passesKidCeiling(kidsMovie(age), null, null), age)
        }
        assertEquals(3, RatingMap.levelOf("12"))
        assertEquals(6, RatingMap.levelOf("18+"))
        assertEquals(2, RatingMap.levelOf("TV-Y/6"))
        // A country prefix names the system: DE-6 is the XML's DVB 6.
        assertEquals(1, RatingMap.levelOf("DE-6"))
        assertEquals(RatingTier.Kids, RatingTier.of(kidsMovie("DE-6"), null))
    }

    @Test
    fun unknownAndUnratedStayAdultUnderEveryCeiling() {
        val unknown =
            listOf(
                null,
                "",
                "NR",
                "Not Rated",
                "Unrated",
                "Passed",
                "Approved",
                "CA-E",
                "CA-Exempt",
                "E",
                "DE-NR",
                "XX-12",
                "DE-12A",
                "DE-6/XX",
            ) + MOCK_UNKNOWNS + MOVIELABS_ONLY
        val items = unknown.mapIndexed { index, rating -> kidsMovie(rating, index + 1) }
        val library = snapshot(items = items)
        for (entry in items) {
            assertNull(RatingMap.levelOf(entry.officialRating), "${entry.officialRating}")
            assertEquals(RatingTier.Adult, RatingTier.of(entry, null), "${entry.officialRating}")
        }
        for (ceiling in listOf(null) + RatingLadder.names) {
            val channel = rules(listOf(Source.Library(LIB)), maxRating = ceiling)
            for (policy in listOf(PoolPolicy.KidsOnly, PoolPolicy.Custom, PoolPolicy.GrownUp)) {
                val pool = PoolEvaluator.evaluate(channel, library, policy)
                if (ceiling != null || policy == PoolPolicy.KidsOnly) {
                    assertTrue(pool.items.isEmpty(), "an unknown rating under $ceiling, $policy: ${pool.items}")
                }
            }
        }
    }

    @Test
    fun theMockLibraryUnknownsStayUnknownAndPg13IsAnAlias() {
        for (rating in MOCK_UNKNOWNS) assertNull(RatingMap.levelOf(rating), rating)
        assertEquals(3, RatingMap.levelOf("PG13"))
        assertEquals(3, RatingMap.levelOf("SG-PG13"))
    }

    // The XML is the only source: a rating MovieLabs or another table has and the XML lacks stays unknown (#987).
    @Test
    fun aRatingOutsideTheXmlStaysUnknown() {
        for (rating in MOVIELABS_ONLY) assertNull(RatingMap.levelOf(rating), rating)
    }

    // TV and PG are country codes too (Tuvalu, Papua New Guinea): the table has neither, and US names read first.
    @Test
    fun tv14IsNeverReadAsTuvalu() {
        val codes = AndroidTvRatingSystems.SYSTEMS.flatMap { it.countries }.toSet()
        for (code in listOf("TV", "PG", "NC", "US")) assertFalse(code in codes, code)
        assertEquals(4, RatingMap.levelOf("TV-14"))
        assertEquals(6, RatingMap.levelOf("TV-14/TV-MA"))
        assertEquals(5, RatingMap.levelOf("PG-13/R"))
    }

    @Test
    fun aCombinedCountryRatingTakesItsHighestPart() {
        assertEquals(5, RatingMap.levelOf("NL-12/NL-16"))
        assertEquals(6, RatingMap.levelOf("DE-6/DE-18"))
        assertEquals(4, RatingMap.levelOf("BR-14/TV-PG"))
        assertNull(RatingMap.levelOf("DE-6/GB-U"))
    }

    // An unprefixed spelling found in more than one country takes the highest level; a single letter, a bare number
    // and an exempt rating need their country prefix.
    @Test
    fun anUnprefixedSpellingIsUnambiguousOrTakesItsHighest() {
        assertEquals(3, RatingMap.levelOf("SAM 13"))
        assertEquals(3, RatingMap.levelOf("SAM13"))
        assertEquals(0, RatingMap.levelOf("GA"))
        assertEquals(3, RatingMap.levelOf("PG-12"))
        assertEquals(5, RatingMap.levelOf("NC16"))
        assertEquals(7, RatingMap.levelOf("X-Rated"))
        assertNull(RatingMap.levelOf("P"))
        assertNull(RatingMap.levelOf("L"))
        // US-first: unprefixed PG is the US PG (CA PG is 14 in the XML), C and M stay at their strictest.
        assertEquals(2, RatingMap.levelOf("PG"))
        assertEquals(4, RatingMap.levelOf("CA-PG"))
        assertEquals(5, RatingMap.levelOf("C"))
        assertEquals(0, RatingMap.levelOf("CA-C"))
    }

    @Test
    fun anXmlRatingAtAgeEighteenOrAboveIsNeverBelowAdult() {
        eachRating { key, hint ->
            if (hint >= 18) assertNotNull(RatingMap.levelOf(key)).let { assertTrue(it >= 6, key) }
        }
    }

    private companion object {
        /** The mock-library strings of the 2026-11-20 run that no system identifies (#987), besides the PG13 alias. */
        val MOCK_UNKNOWNS = listOf("U/A 13+", "B-15", "B", "10AP", "T")

        /** Ratings of other tables (MovieLabs, BBFC, FSK, Eirin, ACB film) that the Android TV XML does not have. */
        val MOVIELABS_ONLY =
            listOf(
                "GB-12A",
                "GB-15",
                "gb/15",
                "12A",
                "U",
                "FSK 16",
                "DE-FSK 12",
                "JP-PG12",
                "JP-R15+",
                "AU-MA15+",
                "MA 15+",
            )
    }
}
