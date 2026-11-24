// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // A table of expected levels: the numbers are the ladder's.

package tv.stationbreak.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Named non-US rating vectors (#987), written BY HAND from the issue's ladder and the ages of the Android TV ratings
 * (not read from [AndroidTvRatingSystems], not computed by [RatingMap]). The ladder: 0 ALL, 1 is ages 1 to 7, 2 is 8
 * to 10, 3 is 11 to 13, 4 is 14, 5 is 15 to 17, 6 is 18 and up, 7 is Explicit. `null` is unknown, so adult.
 * `kid-hold-10.json` holds the full table; this is the part a person can read and check against the ages.
 */
class NonUsRatingVectors {
    private fun check(
        group: String,
        vararg rows: Pair<String, Int?>,
    ) {
        for ((rating, level) in rows) assertEquals(level, RatingMap.levelOf(rating), "$group: '$rating'")
    }

    @Test
    fun argentinaAustraliaBrazil() {
        check(
            "AR",
            "AR-ATP" to 0,
            "AR-SAM 13" to 3,
            "AR-SAM13" to 3,
            "AR-SAM 16" to 5,
            "AR-SAM 18" to 6,
        )
        check(
            "AU",
            "AU-P" to 1,
            "AU-C" to 1,
            "AU-G" to 0,
            // The owner's override: PG is 10 (the XML's hint is 15). The one rating below its hint.
            "AU-PG" to 2,
            "AU-M" to 5,
            "AU-MA" to 5,
            "AU-AV" to 5,
            "AU-R" to 7,
        )
        check(
            "BR",
            "BR-L" to 0,
            "BR-GA" to 0,
            "BR-10" to 2,
            "BR-PG-10" to 2,
            "BR-12" to 3,
            "BR-14" to 4,
            "BR-16" to 5,
            "BR-18" to 6,
        )
    }

    @Test
    fun canada() {
        check(
            "CA",
            "CA-C" to 0,
            "CA-G" to 0,
            "CA-C8" to 2,
            "CA-8ANS+" to 2,
            "CA-PG" to 4,
            "CA-14+" to 4,
            "CA-13ANS+" to 3,
            "CA-16ANS+" to 5,
            "CA-18+" to 6,
            "CA-18ANS+" to 6,
            // Exempt (news, sports, talk) is not a classification: unknown, not the XML's 0.
            "CA-E" to null,
            "CA-EXEMPT" to null,
            "EXEMPT" to null,
        )
    }

    @Test
    fun europeanBroadcastAgesAtEveryEdge() {
        // The DVB ages: 4 to 7 is level 1, 8 to 10 is 2, 11 to 13 is 3, 14 is 4, 15 to 17 is 5, 18 is 6.
        check(
            "DE",
            "DE-4" to 1,
            "DE-6" to 1,
            "DE-7" to 1,
            "DE-8" to 2,
            "DE-10" to 2,
            "DE-11" to 3,
            "DE-12" to 3,
            "DE-13" to 3,
            "DE-14" to 4,
            "DE-15" to 5,
            "DE-16" to 5,
            "DE-17" to 5,
            "DE-18" to 6,
            // The DVB list stops at 18: an age the XML does not list is unknown, so adult.
            "DE-19" to null,
            "DE-20" to null,
            "DE-12+" to 3,
        )
        check("NL", "NL-6" to 1, "NL-9" to 2, "NL-12" to 3, "NL-14" to 4, "NL-16" to 5, "NL-18" to 6)
        check("CN and JP", "CN-6" to 1, "CN-18" to 6, "JP-6" to 1, "JP-12" to 3, "JP-15" to 5, "JP-18" to 6)
        // The adult-only labels are Explicit (#1124).
        check("JP adult-only", "JP-19" to 7, "JP-20" to 7)
    }

    @Test
    fun spainFranceKoreaNewZealandSingaporeThailand() {
        check(
            "ES",
            "ES-ALL" to 0,
            "ES-7" to 1,
            "ES-12" to 3,
            "ES-16" to 5,
            "ES-18" to 6,
            "ES-CHILDREN" to 3,
            "ES-C" to 3,
            // X-rated is Explicit (the XML's hint of 18 rounds to 18; its name says more).
            "ES-X" to 7,
            "ES-X-RATED" to 7,
        )
        check("FR", "FR-U" to 0, "FR-UNIVERSAL" to 0, "FR-10" to 2, "FR-12" to 3, "FR-16" to 5, "FR-18" to 6)
        check("KR", "KR-ALL" to 0, "KR-7" to 1, "KR-12" to 3, "KR-15" to 5, "KR-19" to 7)
        // NZ PGR and AO have a hint of 0 in the XML; their names say otherwise, so they fail upward.
        check("NZ", "NZ-G" to 0, "NZ-PGR" to 2, "NZ-AO" to 7, "NZ-16" to 5)
        check("SG", "SG-G" to 0, "SG-PG" to 2, "SG-PG13" to 3, "SG-NC16" to 5, "SG-M18" to 6, "SG-R21" to 7)
        check("TH", "TH-4" to 1, "TH-6" to 1, "TH-10" to 2, "TH-13" to 3, "TH-15" to 5, "TH-18" to 6, "TH-19" to 6)
    }

    @Test
    fun oddSpellingsOfOneRatingAgree() {
        val german = listOf("DE-12", "de-12", "De-12", "de:12", "DE:12", "DE: 12", "de/12", "  DE-12 ", "DE\u201312")
        for (spelling in german) {
            assertEquals(3, RatingMap.levelOf(spelling), "'$spelling'")
        }
        for (spelling in listOf("AU-PG", "au-pg", "AU:PG", "au/pg", "Rated AU-PG")) {
            assertEquals(2, RatingMap.levelOf(spelling), "'$spelling'")
        }
    }

    // Ratings of the issue's ladder that the Android TV XML does not define. Under the 2026-11-23 decision they stay
    // unknown, so adult (issue-987-report.md, "What stays unknown"). Do not fill these from another source.
    @Test
    fun ratingsTheXmlLacksStayUnknown() {
        for (
        rating in
        listOf(
            // UK (BBFC)
            "U",
            "12A",
            "GB-15",
            "gb/15",
            "GB-U",
            "GB-12A",
            // Germany's FSK spellings (only the DVB numbers map)
            "FSK 16",
            "DE-FSK 12",
            "FSK 0",
            // Japan's Eirin, Australia's film ratings
            "JP-PG12",
            "JP-R15+",
            "MA15+",
            "AU-MA15+",
            "MA 15+",
            // A US-prefixed name, a country name, a space as the separator
            "US-PG",
            "Germany: FSK 18",
            "AU PG",
            // Single letters mean something different in each country, so they need a country prefix
            "P",
            "L",
            "E",
            "T",
            "B",
            // The mock-library strings
            "U/A 13+",
            "B-15",
            "10AP",
            // Unrated spellings and blanks
            "NR",
            "Not Rated",
            "Unrated",
            "Passed",
            "Approved",
            "",
            "   ",
        )
        ) {
            assertEquals(null, RatingMap.levelOf(rating), "'$rating'")
        }
        assertEquals(null, RatingMap.levelOf(null))
    }

    @Test
    fun theIssueCasesThatStillHold() {
        check(
            "issue",
            "18" to 6,
            "18+" to 6,
            "XXX" to 7,
            "TV-14" to 4,
            "PG13" to 3,
            "PG-13" to 3,
            "GP" to 2,
            "12" to 3,
            "TV-14/TV-MA" to 6,
            "Rated PG" to 2,
        )
        // A bare age caps at Family (#1006, owner decision 2026-11-23): never kids, whatever the age.
        check("bare ages", "0" to 2, "6" to 2, "6+" to 2, "7" to 2, "7+" to 2, "10" to 2, "TV-Y/6" to 2)
    }

    @Test
    fun listsTakeTheirHighestPartAndAnyUnknownPartIsUnknown() {
        check(
            "lists",
            "DE-12/DE-16" to 5,
            "PG-13/R" to 5,
            "DE-12/FSK 16" to null,
            "AU-PG/AU-R" to 7,
        )
    }

    @Test
    fun anUnprefixedSpellingThatNoOneElseSharesReadsAsItsCountryDoes() {
        check(
            "unprefixed",
            "ATP" to 0,
            "UNIVERSAL" to 0,
            "C8" to 2,
            "PGR" to 2,
            "SAM 13" to 3,
            "13ANS+" to 3,
            "PG-14" to 4,
            "NC16" to 5,
            "M18" to 6,
            "R21" to 7,
            "X-RATED" to 7,
        )
    }

    @Test
    fun theTiersOfAKidsGenreTitleAtEachEdge() {
        val tiers =
            listOf(
                "DE-6" to RatingTier.Kids,
                "AU-G" to RatingTier.Kids,
                "BR-L" to RatingTier.Kids,
                // A bare age has no system: never kids.
                "6" to RatingTier.Family,
                "AU-PG" to RatingTier.Family,
                "DE-10" to RatingTier.Family,
                "DE-12" to RatingTier.Teen,
                "KR-12" to RatingTier.Teen,
                "DE-16" to RatingTier.Adult,
                "DE-18" to RatingTier.Adult,
                "FSK 16" to RatingTier.Adult,
                "CA-EXEMPT" to RatingTier.Adult,
                "NZ-AO" to RatingTier.Explicit,
                "ES-X" to RatingTier.Explicit,
            )
        for ((rating, tier) in tiers) {
            val title = movie(idOf(1), rating = rating, genres = listOf("Kids"))
            assertEquals(tier, RatingTier.of(title, null), rating)
        }
    }
}
