// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.domain.lineup.ChannelNumbers
import kotlin.test.Test
import kotlin.test.assertEquals

class DraftChoicesTest {
    @Test
    fun grownUpCeilingsAreOneNamePerTierLowestFirst() {
        assertEquals(
            listOf("TV-Y", "TV-Y7", "TV-PG", "PG-13", "TV-14", "R", "TV-MA", "TV-X"),
            DraftChoices.ratingCeilings(kids = false),
        )
    }

    @Test
    fun kidsCeilingsStopAtTheKidsHold() {
        assertEquals(listOf("G", "TV-Y7", "PG"), DraftChoices.ratingCeilings(kids = true))
    }

    @Test
    fun aStoredNameFindsItsTierWhateverItsSpelling() {
        assertEquals(1, DraftChoices.ratingTier("TV-Y7-FV", kids = false))
        assertEquals(2, DraftChoices.ratingTier("PG", kids = false))
        assertEquals(null, DraftChoices.ratingTier(null, kids = false))
        assertEquals(2, DraftChoices.ratingTier("PG", kids = true))
        assertEquals(2, DraftChoices.ratingTier("TV-PG", kids = true))
    }

    @Test
    fun theNumberBandsAreTheLineups() {
        assertEquals(600..899, ChannelNumbers.custom)
        assertEquals(1..99, ChannelNumbers.kids)
    }
}
