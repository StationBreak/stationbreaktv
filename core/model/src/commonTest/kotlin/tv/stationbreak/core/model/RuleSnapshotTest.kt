// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Spec 23, D8: the snapshot holds item, series and collection names, as stored text. */
class RuleSnapshotTest {
    private fun id(n: Int) = CanonicalId.parse(n.toString(16).padStart(32, '0'))

    private val scheduleItem =
        ScheduleItem(
            id = id(1),
            kind = ScheduleItemKind.Movie,
            runtimeMs = 1_800_000L,
            createdAtMs = 0L,
            deletedAtMs = null,
            premiereDate = null,
            productionYear = null,
            seriesId = null,
            seasonNumber = null,
            episodeNumber = null,
        )

    @Test
    fun namesDefaultToNullAndAreNotFolded() {
        val item = RuleItem(scheduleItem, id(900), null, null, listOf("Drama"), emptyList())
        val series = RuleSeries(id(2), null, listOf("Drama"), emptyList())
        val collection = RuleCollection(id(3), emptySet(), emptySet(), emptySet(), emptySet())
        assertNull(item.name)
        assertNull(series.name)
        assertNull(collection.name)

        val mixed = "Show A: The Capital Letters"
        val named = item.copy(name = mixed)
        val namedSeries = series.copy(name = mixed)
        val folds = NameFolds()
        named.primeFolds(folds)
        namedSeries.primeFolds(folds)

        // Stored text, exactly: priming folds the genres, tags and studios and never the name.
        assertSame(mixed, named.name)
        assertSame(mixed, namedSeries.name)
        assertEquals(mixed, collection.copy(name = mixed).name)
        assertEquals(listOf("drama"), named.foldedGenres)
        assertEquals(1, folds.distinctNames)
    }

    @Test
    fun aNameNeverReachesToString() {
        val secret = "Show A: Secret Name"
        val item = RuleItem(scheduleItem, id(900), null, null, listOf("Drama"), emptyList(), name = secret)
        val series = RuleSeries(id(2), null, listOf("Drama"), emptyList(), name = secret)
        val collection = RuleCollection(id(3), emptySet(), emptySet(), emptySet(), emptySet(), name = secret)
        for (text in listOf(item.toString(), series.toString(), collection.toString())) {
            assertFalse(text.contains("Secret"), text)
            assertTrue(text.contains("name=<name>"), text)
        }
        assertTrue(item.copy(name = null).toString().contains("name=null"))
    }
}
