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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Spec 10, Unit 3: the rule-input snapshot is a plain value of the fixed shape (Granny H1). Its names (spec 23, D8)
 * are covered by `RuleSnapshotTest`.
 */
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
    fun studiosAreEmptyByDefaultAndFoldedLikeGenresOnARuleItemAndASeries() {
        assertEquals(emptyList(), ruleItem(1).studios)
        assertEquals(emptyList(), ruleItem(1).foldedStudios)

        val item = ruleItem(1).copy(studios = listOf("A Studio"))
        assertEquals(listOf("A Studio"), item.studios)
        assertEquals(listOf(foldNameAscii("A Studio")), item.foldedStudios)

        val series = RuleSeries(id(7), null, emptyList(), emptyList(), studios = listOf("B Network"))
        assertEquals(listOf(foldNameAscii("B Network")), series.foldedStudios)
        assertEquals(emptyList(), RuleSeries(id(8), null, emptyList(), emptyList()).studios)
    }

    @Test
    fun primingFoldsAlsoPrimesStudios() {
        val folds = NameFolds()
        val item = ruleItem(1).copy(studios = listOf("A Studio"))
        val series = RuleSeries(id(7), null, emptyList(), emptyList(), studios = listOf("B Network"))

        item.primeFolds(folds)
        series.primeFolds(folds)

        assertEquals(listOf(foldNameAscii("A Studio")), item.foldedStudios)
        assertEquals(listOf(foldNameAscii("B Network")), series.foldedStudios)
    }

    // ADR-0026: the fold-2 keys sit beside the fold-1 ones; fold 1 is unchanged.
    @Test
    fun studiosHaveFoldTwoKeysBesideTheFoldOneKeys() {
        val item = ruleItem(1).copy(studios = listOf("Walt\u00A0Disney", "Pix\u2065ar"))
        assertEquals(listOf("walt\u00A0disney", "pix\u2065ar"), item.foldedStudios)
        assertEquals(listOf("waltdisney", "pixar"), item.foldedStudios2)
        assertFalse(item.hasEmptyStudioKey2)

        val series = RuleSeries(id(7), null, emptyList(), emptyList(), studios = listOf("B\u3164Network"))
        assertEquals(listOf("b\u3164network"), series.foldedStudios)
        assertEquals(listOf("bnetwork"), series.foldedStudios2)
        assertEquals(emptyList(), ruleItem(2).foldedStudios2)
        assertFalse(ruleItem(2).hasEmptyStudioKey2)
    }

    // The empty-key rule (applied by format 4, a later part) needs to know whether an item has a studio whose key is
    // empty; such a name has no key in the list.
    @Test
    fun aStudioWhoseFoldTwoKeyIsEmptyIsRecorded() {
        val item = ruleItem(1).copy(studios = listOf("Pixar", "\u2800\u3164"))
        assertEquals(listOf("pixar"), item.foldedStudios2)
        assertTrue(item.hasEmptyStudioKey2)
        val series = RuleSeries(id(7), null, emptyList(), emptyList(), studios = listOf("\u3164"))
        assertEquals(listOf("\u3164"), series.foldedStudios)
        assertTrue(series.hasEmptyStudioKey2)
    }

    @Test
    fun primingFoldsAlsoPrimesFoldTwoKeysSharedAcrossTheSnapshot() {
        val folds = NameFolds()
        val one = ruleItem(1).copy(studios = listOf("Walt Disney", "Pixar"))
        val two = ruleItem(2).copy(studios = listOf("Walt Disney", "Pixar"))
        val single = ruleItem(3).copy(studios = listOf("Pixar"))
        val series = RuleSeries(id(7), null, emptyList(), emptyList(), studios = listOf("\u3164", "Walt  Disney"))

        listOf(one, two, single).forEach { it.primeFolds(folds) }
        series.primeFolds(folds)

        assertEquals(listOf("waltdisney", "pixar"), one.foldedStudios2)
        assertSame(one.foldedStudios2, two.foldedStudios2)
        assertSame(one.foldedStudios2[0], series.foldedStudios2[0])
        // A one-word list has the same keys under both folds: one list is held, not two.
        assertSame(single.foldedStudios, single.foldedStudios2)
        assertTrue(series.hasEmptyStudioKey2)
        assertFalse(one.hasEmptyStudioKey2)
    }
}
