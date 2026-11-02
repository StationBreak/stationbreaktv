// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A pool depends on the rules' filters, and the day pool on the runtime filter too. The rules are version 2 and built
 * here, because the lineup codec keeps stored version 2 channels unusable for now.
 */
class LineupDaysFiltersTest {
    private val days = LineupDays(TestCatalog)
    private val atMs = NOON.toEpochMilliseconds()

    private fun year(
        entry: RuleItem,
        year: Int,
    ) = entry.copy(item = entry.item.copy(productionYear = year))

    private fun v2Rules(
        name: String,
        filters: Filters,
        vararg sources: Source,
    ): ChannelRules {
        val seed = Seed(name.hashCode().toULong())
        return ChannelRules(2, name, seed, sources.toList(), null, null, null, timing(), null, filters)
    }

    private fun custom(
        number: Int,
        rules: ChannelRules,
    ) = channel(rules.name.orEmpty(), number, ChannelState.On, ChannelSource.Custom(rules))

    private fun snapshotOf(
        lineup: Lineup,
        vararg items: RuleItem,
    ): Pair<List<Scheduled>, Map<Long, RuleSnapshot>> {
        val scheduled = days.scheduled(lineup, atMs)
        val snapshots =
            scheduled.map { it.cutoffMs }.toSet().associateWith { cutoff ->
                val sorted = items.sortedBy { it.item.id.value }
                RuleSnapshot(cutoff, setOf(LIBRARY_ID), sorted, emptyMap(), emptyMap(), true)
            }
        return scheduled to snapshots
    }

    @Test
    fun twoChannelsWithTheSameSourcesAndDifferentFiltersGetDifferentPools() {
        val nineties = year(movie(1, "Comedy"), 1990)
        val noughties = year(movie(2, "Comedy"), 2005)
        val comedy = Source.Genre("Comedy")
        val older = custom(600, v2Rules("older", Filters(null, 1980, 1999, null, null, null), comedy))
        val newer = custom(610, v2Rules("newer", Filters(null, 2000, 2010, null, null, null), comedy))
        val lineup = lineupOf(older, newer)
        val (scheduled, snapshots) = snapshotOf(lineup, nineties, noughties)

        val built = days.days(lineup, scheduled, snapshots)
        val pools = built.associate { it.channel.id.value to it.pool.map { item -> item.id } }

        assertEquals(listOf(nineties.item.id), pools.getValue("older"))
        assertEquals(listOf(noughties.item.id), pools.getValue("newer"))
    }

    @Test
    fun airingNowhereSeparatesRuntimeFilters() {
        val long = movie(1, "Comedy")
        val short = movie(2, "Drama", runtimeMs = 1_800_000L)
        val comedy = Source.Genre("Comedy")
        val filtered = custom(600, v2Rules("filtered", Filters(null, null, null, 60, null, null), comedy))
        val plain = custom(610, v2Rules("plain", Filters(null, null, null, null, null, null), comedy))
        val lineup = lineupOf(filtered, plain)
        val (scheduled, snapshots) = snapshotOf(lineup, long, short)

        // The short film is airable under the plain channel's settings and in no channel's pool.
        assertEquals(1, days.airingNowhere(lineup, scheduled, snapshots))
    }
}
