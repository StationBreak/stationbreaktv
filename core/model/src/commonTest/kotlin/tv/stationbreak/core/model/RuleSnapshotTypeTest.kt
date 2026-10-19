// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Spec 10, Unit 3: the rule-input snapshot is a plain value of the fixed shape (Granny H1), with no names in it. */
class RuleSnapshotTypeTest {
    private fun id(n: Int) = CanonicalId.parse(n.toString(16).padStart(32, '0'))

    private fun scheduleItem(
        n: Int,
        seriesId: CanonicalId? = null,
    ) = ScheduleItem(
        id = id(n),
        kind = if (seriesId == null) ScheduleItemKind.Movie else ScheduleItemKind.Episode,
        runtimeMs = 1_800_000L,
        createdAtMs = 0L,
        deletedAtMs = null,
        premiereDate = null,
        productionYear = null,
        seriesId = seriesId,
        seasonNumber = null,
        episodeNumber = null,
    )

    private fun ruleItem(
        n: Int,
        rating: String? = null,
    ) = RuleItem(
        item = scheduleItem(n),
        libraryId = id(900),
        seasonId = null,
        officialRating = rating,
        genres = listOf("Drama", "Comedy"),
        tags = listOf("noir"),
    )

    @Test
    fun aRuleItemComposesAScheduleItemBuiltThroughItsOwnChecks() {
        val item = ruleItem(1)

        assertEquals(scheduleItem(1), item.item)
        // The composed item is the scheduler's own type, so a bad item cannot be built to put in one.
        assertFailsWith<IllegalArgumentException> {
            scheduleItem(2).copy(kind = ScheduleItemKind.Movie, seriesId = id(3))
        }
    }

    @Test
    fun aRuleItemKeepsTheSeasonAndTheOrderedLists() {
        val item = ruleItem(1).copy(seasonId = id(5), genres = listOf("b", "a"), tags = listOf("z", "y"))

        assertEquals(id(5), item.seasonId)
        assertEquals(listOf("b", "a"), item.genres)
        assertEquals(listOf("z", "y"), item.tags)
        assertNull(ruleItem(1).seasonId)
    }

    @Test
    fun aNullAnEmptyAndABlankRatingStayThreeValues() {
        val ratings = listOf<String?>(null, "", " ").map { ruleItem(1, it) }

        assertEquals(3, ratings.toSet().size)
        assertNotEquals(ratings[0], ratings[1])
        assertNotEquals(ratings[1], ratings[2])
        assertNull(ratings[0].officialRating)
        assertEquals("", ratings[1].officialRating)
        assertEquals(" ", ratings[2].officialRating)
    }

    @Test
    fun aRuleSeriesAndARuleCollectionAreValues() {
        val series = RuleSeries(id(10), "TV-14", listOf("Drama"), listOf("t"))
        val collection = RuleCollection(id(20), setOf(id(1)), setOf(id(2)), setOf(id(3)), setOf(id(4)))

        assertEquals(RuleSeries(id(10), "TV-14", listOf("Drama"), listOf("t")), series)
        assertEquals(RuleCollection(id(20), setOf(id(1)), setOf(id(2)), setOf(id(3)), setOf(id(4))), collection)
        assertNotEquals(collection, collection.copy(seasons = emptySet()))
    }

    @Test
    fun aSnapshotKeepsItsCutoffItsLibrariesAndWhetherCollectionsAreSynced() {
        val snapshot =
            RuleSnapshot(
                cutoffMs = 1_000L,
                libraries = setOf(id(900)),
                items = listOf(ruleItem(1)),
                series = mapOf(id(10) to RuleSeries(id(10), null, emptyList(), emptyList())),
                collections = mapOf(id(20) to RuleCollection(id(20), emptySet(), emptySet(), emptySet(), emptySet())),
                collectionsSynced = true,
            )

        assertEquals(1_000L, snapshot.cutoffMs)
        assertEquals(setOf(id(900)), snapshot.libraries)
        assertEquals(listOf(ruleItem(1)), snapshot.items)
        assertEquals(setOf(id(10)), snapshot.series.keys)
        assertEquals(setOf(id(20)), snapshot.collections.keys)
        assertEquals(snapshot, snapshot.copy())
        assertNotEquals(snapshot, snapshot.copy(collectionsSynced = false))
    }

    @Test
    fun noTypeCarriesAName() {
        val snapshot =
            RuleSnapshot(
                0L,
                setOf(id(900)),
                listOf(ruleItem(1)),
                mapOf(id(10) to RuleSeries(id(10), null, emptyList(), emptyList())),
                mapOf(id(20) to RuleCollection(id(20), emptySet(), emptySet(), emptySet(), emptySet())),
                false,
            )

        // The text of a data class names every property, so a property called name would show here (spec 12 reads
        // names through its own current-state read, never through the snapshot).
        assertFalse("name" in snapshot.toString().lowercase(), snapshot.toString())
    }
}
