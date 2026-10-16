// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rating ladder, the normalisation and the fail-closed rule (docs/rules-format.md, "Maximum rating"). */
class RatingEligibilityTest {
    /** Written out here on purpose, so the test does not ask the code under test what the ladder is. */
    private val rankOf =
        mapOf(
            "TV-Y" to 0,
            "TV-G" to 0,
            "G" to 0,
            "Approved" to 0,
            "TV-Y7" to 1,
            "TV-Y7-FV" to 1,
            "TV-PG" to 2,
            "PG" to 2,
            "PG-13" to 3,
            "TV-14" to 4,
            "R" to 5,
            "TV-MA" to 6,
            "NC-17" to 6,
            "TV-X" to 7,
            "TV-AO" to 7,
        )

    @Test
    fun everyLadderEntryAgainstEveryCeilingFollowsTheRanks() {
        assertEquals(15, rankOf.size)
        for ((rating, rank) in rankOf) {
            for ((ceiling, ceilingRank) in rankOf) {
                assertEquals(rank <= ceilingRank, RatingEligibility.isEligible(rating, ceiling), "$rating under $ceiling")
            }
        }
    }

    @Test
    fun theLadderInCodeIsTheDocumentedTable() {
        assertEquals(
            listOf(
                listOf("TV-Y", "TV-G", "G", "Approved"),
                listOf("TV-Y7", "TV-Y7-FV"),
                listOf("TV-PG", "PG"),
                listOf("PG-13"),
                listOf("TV-14"),
                listOf("R"),
                listOf("TV-MA", "NC-17"),
                listOf("TV-X", "TV-AO"),
            ),
            RatingLadder.tiers,
        )
        assertEquals(rankOf.keys, RatingLadder.names)
    }

    @Test
    fun aDescriptorNeverChangesTheTier() {
        assertEquals(4, RatingLadder.rankOf("TV-14-V"))
        assertEquals(2, RatingLadder.rankOf("TV-PG-DLSV"))
        assertEquals(6, RatingLadder.rankOf("TV-MA-LSV"))
        assertTrue(RatingEligibility.isEligible("TV-14-V", "TV-14"))
        assertFalse(RatingEligibility.isEligible("TV-14-V", "PG-13"))
        assertTrue(RatingEligibility.isEligible("TV-PG-DLSV", "PG"))
        assertFalse(RatingEligibility.isEligible("TV-PG-DLSV", "TV-Y7"))
    }

    @Test
    fun onlyJellyfinsDescriptorGroupsAreRecognised() {
        // Jellyfin 12.1 lists, for TV-PG and TV-14, every non-empty run of D, L, S, V in that order, and for TV-MA the runs
        // of L, S, V. Anything else is not a rating it knows, so it has no rank here either (and is blocked).
        for (bad in listOf("TV-MA-D", "TV-MA-DL", "TV-G-V", "TV-Y-FV", "TV-Y7-V", "TV-14-VD", "TV-14-VV", "TV-14-", "TV-14--V")) {
            assertNull(RatingLadder.rankOf(bad), bad)
        }
        for (bad in listOf("TV-14-X", "TV-14V", "TV-14 V", "TV-PG-F", "TV-X-V", "TV-Y7-FV-V", "TV-14-DLSVV")) {
            assertNull(RatingLadder.rankOf(bad), bad)
        }
    }

    @Test
    fun approvedIsGAndTheCaseOfTheLettersDoesNotMatter() {
        assertEquals(0, RatingLadder.rankOf("Approved"))
        assertEquals(0, RatingLadder.rankOf("APPROVED"))
        assertEquals("Approved", RatingLadder.normalise("approved"))
        assertEquals(3, RatingLadder.rankOf("pg-13"))
        assertEquals("PG-13", RatingLadder.normalise("pg-13"))
        assertEquals("TV-14", RatingLadder.normalise("tv-14-v"))
        assertTrue(RatingEligibility.isEligible("pg-13", "PG-13"))
        assertTrue(RatingEligibility.isEligible("Approved", "G"))
    }

    @Test
    fun spacesAreTrimmedButNothingElseIs() {
        assertEquals(2, RatingLadder.rankOf("  PG  "))
        assertNull(RatingLadder.rankOf("\tPG"))
        assertNull(RatingLadder.rankOf("PG "))
        assertNull(RatingLadder.rankOf("PG\n"))
        assertNull(RatingLadder.rankOf("P G"))
    }

    @Test
    fun onlyAsciiLettersAreCaseFolded() {
        assertNull(RatingLadder.rankOf("ＰＧ")) // full-width PG
        assertNull(RatingLadder.rankOf("tv-14K")) // Kelvin sign is not an ASCII letter
    }

    @Test
    fun unratedUnknownAndOtherCountrySystemsAreBlockedUnderAnyCeiling() {
        val unknown = listOf("NR", "Unrated", "Not Rated", "N/A", "", " ", "FSK 16", "Rated PG", "US:PG-13", "13", "16+", "M", "X", "GP")
        for (rating in unknown) {
            assertNull(RatingLadder.rankOf(rating), rating)
            for (ceiling in rankOf.keys) assertFalse(RatingEligibility.isEligible(rating, ceiling), "'$rating' under $ceiling")
        }
        for (ceiling in rankOf.keys) assertFalse(RatingEligibility.isEligible(null, ceiling), "no rating under $ceiling")
    }

    @Test
    fun theCeilingPG13AllowsPGAndBlocksTV14() {
        assertTrue(RatingEligibility.isEligible("PG", "PG-13"))
        assertTrue(RatingEligibility.isEligible("PG-13", "PG-13"))
        assertFalse(RatingEligibility.isEligible("TV-14", "PG-13"))
        assertFalse(RatingEligibility.isEligible("R", "PG-13"))
    }

    @Test
    fun anEpisodeWithNoRatingOfItsOwnUsesItsSeriesRating() {
        assertTrue(RatingEligibility.isEligible(null, "G", "PG"))
        assertTrue(RatingEligibility.isEligible("", "G", "PG"))
        assertFalse(RatingEligibility.isEligible(null, "R", "PG"))
        assertFalse(RatingEligibility.isEligible(null, null, "PG"))
    }

    @Test
    fun anEpisodeThatHasARatingOfItsOwnKeepsItWhateverTheSeriesSays() {
        assertFalse(RatingEligibility.isEligible("TV-14", "G", "PG"))
        assertTrue(RatingEligibility.isEligible("G", "R", "PG"))
        // NR and a blank are ratings of their own that mean "unknown": they are not replaced by the series rating.
        assertFalse(RatingEligibility.isEligible("NR", "G", "PG"))
        assertFalse(RatingEligibility.isEligible(" ", "G", "PG"))
    }

    @Test
    fun aCeilingThatIsNotOnTheLadderIsAProgrammingError() {
        for (bad in listOf("PG-14", "pg-13", "tv-14", "NR", "", " PG", "TV-14-V")) {
            assertFailsWith<IllegalArgumentException>(bad) { RatingEligibility.isEligible("G", bad) }
            assertFailsWith<IllegalArgumentException>(bad) { RatingEligibility.isEligible("G", "G", bad) }
        }
    }
}
