// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Until the engine and the scheduler read format 3 (spec 23, Tasks 6 and 7), rules that use it are read and written but
 * never scheduled: each place that would otherwise ignore a format 3 part refuses it, so nothing quietly airs wrongly.
 */
class Format3NotScheduledTest {
    private fun filters(
        tags: List<String>? = null,
        episodeMax: Int? = null,
    ) = Filters(null, null, null, null, null, null, tags = tags, episodeMaxRuntimeMinutes = episodeMax)

    @Test
    fun aCuratedChannelHasNoSchedulerSettingsYet() {
        val curated = rules(listOf(Source.Library(LIB)), formatVersion = 3, ordering = Ordering(OrderingMode.Curated))
        assertFailsWith<IllegalArgumentException> { curated.toSchedulerSettings() }
    }

    @Test
    fun aPerKindRuntimeIsNotIgnored() {
        val perKind = rules(listOf(Source.Library(LIB)), formatVersion = 3, filters = filters(episodeMax = 30))
        assertFailsWith<IllegalStateException> { perKind.toSchedulerSettings() }
    }

    @Test
    fun aStudioSourceIsNotMatchedYet() {
        val studio = rules(listOf(Source.Studio("Studio A")), formatVersion = 3)
        assertFailsWith<IllegalStateException> { PoolEvaluator.evaluate(studio, snapshot(items = emptyList())) }
    }

    @Test
    fun aVersion3FilterIsNotPassedYet() {
        val tagged = rules(listOf(Source.Library(LIB)), formatVersion = 3, filters = filters(tags = listOf("Tag A")))
        assertFailsWith<IllegalStateException> { PoolEvaluator.evaluate(tagged, snapshot(items = emptyList())) }
    }
}
