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
    // 14, 16 is 15 to 16, 18 is 17 and up: kid-hold-14, #1006). Written here on its own, not with RatingMap's code.
    private fun rungOf(age: Int): Int =
        when {
            age <= 0 -> 0
            age <= 7 -> 1
            age <= 10 -> 2
            age <= 13 -> 3
            age == 14 -> 4
            age <= 16 -> 5
            else -> 6
        }

    // The StationBreak decisions on top of the XML (CountryRatingTables' overrides): the AU PG override is the one
    // rating below its hint (owner decision, #987); the others fail up from a hint the rating's own name contradicts,
    // and the adult-only labels AU R, SG R21 and TH 19 are Explicit (#1124, #1128). KR 19 and JP 19 and 20 are Adult
    // (#987).
    private val overrides: Map<String, Int?> =
        mapOf(
            "AU-PG" to 2,
            "AU-R" to 7,
            "SG-R21" to 7,
            "TH-19" to 7,
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

    // #1124: a custom or grown-up channel's ceiling reads the mapped level, never above it, and under Custom (no hold
    // beyond the young-ceiling tier limit) exactly at or below it.
    @Test
    fun aCeilingPassesARatingExactlyWhenItsMappedLevelIsWithinIt() {
        eachRating { key, _ ->
            val level = RatingMap.levelOf(key) // null for Exempt: it fails every ceiling
            val library = snapshot(items = listOf(movie(idOf(1), rating = key)))
            for (ceiling in RatingLadder.names - "Approved") {
                val channel = rules(listOf(Source.Library(LIB)), maxRating = ceiling)
                val within = level != null && level <= checkNotNull(RatingLadder.rankOf(ceiling))
                for (policy in listOf(PoolPolicy.Custom, PoolPolicy.GrownUp)) {
                    val passes = PoolEvaluator.evaluate(channel, library, policy).items.isNotEmpty()
                    if (passes) assertTrue(within, "$key airs under $ceiling, $policy")
                }
                val custom = PoolEvaluator.evaluate(channel, library, PoolPolicy.Custom).items.isNotEmpty()
                assertEquals(within, custom, "$key under $ceiling, Custom")
            }
        }
    }

    @Test
    fun theAdultOnlyLabelsAreExplicit() {
        for (rating in listOf("AU-R", "SG-R21", "TH-19")) {
            assertEquals(7, RatingMap.levelOf(rating), rating)
            assertTrue(RatingMap.isExplicit(rating), rating)
            assertEquals(RatingTier.Explicit, RatingTier.of(movie(idOf(1), rating = rating), null), rating)
        }
        // Not the US R, nor the labels one rung below.
        for (rating in listOf("R", "AU-M", "SG-M18", "KR-15", "JP-18")) {
            assertFalse(RatingMap.isExplicit(rating), rating)
        }
    }

    // #987: KR 19 and JP 19 and 20 are mainstream adult ratings: Adult (level 6), not Explicit.
    @Test
    fun theMainstreamAdultLabelsAreAdultNotExplicit() {
        for (rating in listOf("KR-19", "JP-19", "JP-20", "JP-19+", "KR 19")) {
            assertFalse(RatingMap.isExplicit(rating), rating)
            assertEquals(RatingTier.Adult, RatingTier.of(movie(idOf(1), rating = rating), null), rating)
        }
        assertEquals(6, RatingMap.levelOf("KR-19"))
        assertEquals(6, RatingMap.levelOf("JP-20"))
        assertTrue(RatingMap.isExplicit("KR-Restricted Screening"))
    }

    // #1128: the same labels with a space after the country code are Explicit too (their level stays unknown, which
    // fails every ceiling), and the spellings that are not adult-only are not.
    @Test
    fun theSpacedAdultOnlyLabelsAreExplicit() {
        val spaced = listOf("AU R", "SG R21", "TH 19", "au r", "Rated SG R21", "PG/AU R")
        for (rating in spaced) {
            assertTrue(RatingMap.isExplicit(rating), rating)
            assertEquals(RatingTier.Explicit, RatingTier.of(movie(idOf(1), rating = rating), null), rating)
        }
        val notAdultOnly =
            listOf("AU PG", "AU M", "SG M18", "KR 15", "KR 19", "JP 19", "JP 20", "TH 18", "US R", "TV MA")
        for (rating in notAdultOnly) {
            assertFalse(RatingMap.isExplicit(rating), rating)
        }
        assertEquals(null, RatingMap.levelOf("SG R21"))
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
        assertEquals(5, RatingMap.levelOf("16+"))
        assertEquals(6, RatingMap.levelOf("18+"))
        assertEquals(2, RatingMap.levelOf("TV-Y/6"))
        // A country prefix names the system: DE-6 is the XML's DVB 6.
        assertEquals(1, RatingMap.levelOf("DE-6"))
        assertEquals(RatingTier.Kids, RatingTier.of(kidsMovie("DE-6"), null))
    }

    // #1006, owner decision 2026-11-23 (confirmed 2026-11-27): the 17+ band is level 6 (18), bare or prefixed; 16 is 5.
    @Test
    fun aMinimumAgeOfSeventeenIsLevelSixNotFive() {
        for (rating in listOf("17", "17+", "DE-17", "DE-17+", "DE:17", "fr/17")) {
            assertEquals(6, RatingMap.levelOf(rating), rating)
        }
        for (rating in listOf("15", "16", "16+", "DE-16")) assertEquals(5, RatingMap.levelOf(rating), rating)
        assertFalse(KidHold.passesKidCeiling(kidsMovie("17"), null, "R"))
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
                "DE-6/XX",
            ) + MOCK_UNKNOWNS + STILL_UNKNOWN
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

    // A rating that neither the XML nor a board's own definition has stays unknown (#987): no other table fills it.
    @Test
    fun aRatingNoBoardDefinesStaysUnknown() {
        for (rating in STILL_UNKNOWN) assertNull(RatingMap.levelOf(rating), rating)
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

    // LOW 2 of the #987 review: one prefix rule for the level and the explicit check, so a `/` prefix fails up too.
    @Test
    fun everyCountrySeparatorAlsoReadsAnExplicitRating() {
        val explicit =
            listOf(
                "GB/R18",
                "DE/X18+",
                "AU/X18+",
                "de:r18",
                "GB - R18",
                "gb / r18",
                "NZ/AO",
                "ES/X",
                "NC - 17",
                "NC -17",
                "NC-  17",
                "NC\t-\t17",
                "R/NC - 17",
                "GB-NC - 17",
                "R - 18",
            )
        for (rating in explicit) {
            assertTrue(RatingMap.isExplicit(rating), rating)
            assertEquals(RatingTier.Explicit, RatingTier.of(kidsMovie(rating), null), rating)
        }
        for (rating in listOf("TV-14/TV-MA", "PG-13/R", "DE/18", "TV-MA", "R/TV-MA")) {
            assertFalse(RatingMap.isExplicit(rating), rating)
        }
        assertEquals(6, RatingMap.levelOf("TV-14/TV-MA"))
        assertEquals(7, RatingMap.levelOf("NZ/AO"))
    }

    @Test
    fun aCombinedCountryRatingTakesItsHighestPart() {
        assertEquals(5, RatingMap.levelOf("NL-12/NL-16"))
        assertEquals(6, RatingMap.levelOf("DE-6/DE-18"))
        assertEquals(4, RatingMap.levelOf("BR-14/TV-PG"))
        assertEquals(1, RatingMap.levelOf("DE-6/GB-U"))
        assertNull(RatingMap.levelOf("DE-6/GB-12PG"))
    }

    // An unprefixed spelling found in more than one country takes the highest level; a single letter, a bare number
    // and an exempt rating need their country prefix.
    @Test
    fun anUnprefixedSpellingIsUnambiguousOrTakesItsHighest() {
        assertEquals(3, RatingMap.levelOf("SAM 13"))
        assertEquals(3, RatingMap.levelOf("SAM13"))
        assertEquals(2, RatingMap.levelOf("GA")) // a bare all-ages word is Family at most (#987)
        assertEquals(0, RatingMap.levelOf("BR-GA"))
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

        /** Spellings no board defines, so they stay unknown (#987): a made-up tier, a code with nothing after it. */
        val STILL_UNKNOWN = listOf("GB-12PG", "FSK-7", "JP-PG15", "AU-MA16+", "UK:", "FSK", "ab", "ab 7", "DE-12A")
    }
}
