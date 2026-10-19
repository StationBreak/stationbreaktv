// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** With no ceiling every item is eligible; a ceiling that is not on the ladder makes the file invalid. */
class NoCeilingTest {
    @Test
    fun withoutACeilingEveryItemIsEligibleRatedOrNot() {
        for (rating in listOf(null, "", " ", "NR", "Unrated", "FSK 16", "G", "TV-MA", "TV-AO", "tv-14-v", "nonsense")) {
            assertTrue(RatingEligibility.isMovieEligible(rating, null), "'$rating'")
        }
    }

    @Test
    fun withoutACeilingAnEpisodeIsEligibleWhateverItsSeriesIs() {
        assertTrue(RatingEligibility.isEpisodeEligible(null, SeriesState.Known(null), null))
        assertTrue(RatingEligibility.isEpisodeEligible("PG-13", SeriesState.Known(null), null))
        assertTrue(RatingEligibility.isEpisodeEligible(null, SeriesState.Known("NR"), null))
        assertTrue(RatingEligibility.isEpisodeEligible(null, SeriesState.Unknown, null))
        assertTrue(RatingEligibility.isEpisodeEligible(null, SeriesState.NoSeries, null))
    }

    @Test
    fun aFileWithNoCeilingReadsWithNoCeiling() {
        assertNull(readOk(minimalRules().text()).maxRating)
    }

    @Test
    fun aCeilingOnTheLadderReadsAndWritesBack() {
        val rules = readOk(minimalRules().with("maxRating", "PG-13").text())
        assertEquals("PG-13", rules.maxRating)
        assertTrue("\"maxRating\": \"PG-13\"" in RulesWriter.write(rules))
    }

    @Test
    fun aCeilingNotOnTheLadderMakesTheFileInvalidAndIsNeverReadAsNoCeiling() {
        for (bad in listOf("PG-14", "NR", "Unrated", "", "approved", "tv-14", "FSK 16", "TV-14-V")) {
            val result = RulesReader.read(minimalRules().with("maxRating", bad).text())
            assertIs<RulesResult.Invalid>(result, "maxRating '$bad' gave $result")
        }
        assertIs<RulesResult.Invalid>(RulesReader.read(minimalRules().with("maxRating", JsonNull).text()))
    }
}
