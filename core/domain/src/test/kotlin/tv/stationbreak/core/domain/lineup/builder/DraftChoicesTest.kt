// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.domain.lineup.ChannelNumbers
import tv.stationbreak.core.domain.lineup.FamilyTier
import kotlin.test.Test
import kotlin.test.assertEquals

class DraftChoicesTest {
    @Test
    fun grownUpCeilingsAreOneNamePerTierLowestFirst() {
        assertEquals(
            listOf("TV-Y", "TV-Y7", "TV-PG", "PG-13", "TV-14", "R", "TV-MA", "TV-X"),
            DraftChoices.ratingCeilings(null),
        )
    }

    @Test
    fun kidsCeilingsStopAtTheKidsHold() {
        assertEquals(listOf("G", "TV-Y7", "PG"), DraftChoices.ratingCeilings(FamilyTier.Tots))
    }

    @Test
    fun aStoredNameFindsItsTierWhateverItsSpelling() {
        assertEquals(1, DraftChoices.ratingTier("TV-Y7-FV", null))
        assertEquals(2, DraftChoices.ratingTier("PG", null))
        assertEquals(null, DraftChoices.ratingTier(null, null))
        assertEquals(2, DraftChoices.ratingTier("PG", FamilyTier.Tots))
        assertEquals(2, DraftChoices.ratingTier("TV-PG", FamilyTier.Tots))
    }

    @Test
    fun theNumberBandsAreTheLineups() {
        assertEquals(600..899, ChannelNumbers.custom)
        assertEquals(1..33, ChannelNumbers.kids)
    }

    @Test
    fun teensCeilingsRunTheLadderUpToTv14() {
        assertEquals(listOf("TV-Y", "TV-Y7", "TV-PG", "PG-13", "TV-14"), DraftChoices.ratingCeilings(FamilyTier.Teens))
        assertEquals(listOf("G", "TV-Y7", "PG"), DraftChoices.ratingCeilings(FamilyTier.Kids))
    }

    @Test
    fun teensPlaceAStoredCeilingOnTheLadder() {
        assertEquals(2, DraftChoices.ratingTier("PG", FamilyTier.Teens))
        assertEquals(4, DraftChoices.ratingTier("TV-14", FamilyTier.Teens))
        assertEquals(null, DraftChoices.ratingTier(null, FamilyTier.Teens))
    }
}
