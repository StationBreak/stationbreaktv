// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.testing

import tv.stationbreak.core.model.CollectionMemberKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The generated series and collections that sit beside [SyntheticLibrary] for the rule snapshot's size tests. */
class SyntheticRuleInputsTest {
    private val inputs = SyntheticRuleInputs(SyntheticLibrary(size = 15_000), seriesCount = 500, collectionCount = 100)

    @Test
    fun theSameInputsGiveIdenticalSeriesAndMembers() {
        assertEquals(inputs.series(1), SyntheticRuleInputs(SyntheticLibrary(size = 15_000)).series(1))
        assertEquals(inputs.members(3, 2), inputs.members(3, 2))
    }

    @Test
    fun thereAreTheAskedForSeriesWithDistinctCanonicalIds() {
        val series = inputs.series(0)

        assertEquals(500, series.size)
        assertEquals(500, series.map { it.id.value }.toSet().size)
        assertTrue(series.all { it.id.value.length == 32 })
    }

    @Test
    fun aRoundChangesTheRatingOfEverySeriesSoItMakesAVersion() {
        val first = inputs.series(0)
        val second = inputs.series(1)

        assertTrue(first.indices.all { first[it].officialRating != second[it].officialRating })
    }

    @Test
    fun theFirstCollectionHoldsTwoThousandMembersAndTheOthersAboutFifty() {
        assertTrue(inputs.members(0, 0).size in 1_990..2_010)
        assertTrue((1 until 100).all { inputs.members(it, 0).size in 45..55 })
    }

    @Test
    fun aRoundChangesAFewMembersAndNoMemberIsListedTwice() {
        for (j in listOf(0, 1, 57)) {
            val before = inputs.members(j, 0).map { it.id.value }
            val after = inputs.members(j, 1).map { it.id.value }

            assertEquals(before.size, before.toSet().size)
            assertNotEquals(before, after)
            assertTrue(before.toSet().intersect(after.toSet()).size >= before.size - 6)
        }
    }

    @Test
    fun membersComeInSeveralKinds() {
        val kinds = inputs.members(0, 0).map { it.kind }.toSet()

        assertTrue(kinds.containsAll(setOf(CollectionMemberKind.Movie, CollectionMemberKind.Episode, CollectionMemberKind.Series)))
    }
}
