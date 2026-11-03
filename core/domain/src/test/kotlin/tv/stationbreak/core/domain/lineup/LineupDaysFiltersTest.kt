// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A pool depends on the rules' filters, and the day pool on the runtime filter too. The rules are version 2.
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
    fun twoChannelsWithEqualFiltersShareOneEvaluation() {
        val comedy = Source.Genre("Comedy")
        val filters = Filters(null, 1980, 1999, null, null, null)
        val first = custom(600, v2Rules("first", filters, comedy))
        val second = custom(610, v2Rules("second", filters, comedy))
        val lineup = lineupOf(first, second)
        val (scheduled, snapshots) = snapshotOf(lineup, year(movie(1, "Comedy"), 1990))
        var evaluations = 0

        days.days(lineup, scheduled, snapshots, evaluated = { evaluations++ })

        assertEquals(1, evaluations)
    }

    @Test
    fun rulesAndWithheldPoolsShareOneEvaluation() {
        val comedy = Source.Genre("Comedy")
        val filters = Filters(null, 1980, 1999, null, null, null)
        val shown = custom(600, v2Rules("shown", filters, comedy))
        val hidden = shown.copy(id = ChannelId("hidden"), defaultNumber = 610, state = ChannelState.UserHidden)
        val (_, snapshots) = snapshotOf(lineupOf(shown, hidden), year(movie(1, "Comedy"), 1990))
        val withHidden = lineupOf(shown, hidden, leftovers("odds-and-ends", 199, null))
        val withoutHidden = lineupOf(shown, leftovers("odds-and-ends", 199, null))

        // The leftovers withhold the hidden channel's pool and the opt-in recipes'; the hidden pool is the shown one.
        fun evaluations(lineup: Lineup): Int {
            var count = 0
            val scheduledOf = days.scheduled(lineup, atMs)
            days.days(lineup, scheduledOf, snapshots, evaluated = { count++ })
            return count
        }

        assertEquals(evaluations(withoutHidden), evaluations(withHidden))
    }

    @Test
    fun airingNowhereSeparatesRuntimeFilters() {
        val long = movie(1, "Comedy")
        val short = movie(2, "Drama", runtimeMs = 1_800_000L)
        val comedy = Source.Genre("Comedy")
        val filtered = v2Rules("filtered", Filters(null, null, null, 60, null, null), comedy)
        val plain = v2Rules("plain", Filters(null, null, null, null, null, null), comedy)

        // The short film is airable under the plain channel's settings and in no channel's pool, whichever channel
        // comes first: a key that ignored the runtime filter would take the first channel's settings for both.
        for ((first, second) in listOf(filtered to plain, plain to filtered)) {
            val lineup = lineupOf(custom(600, first), custom(610, second))
            val (scheduled, snapshots) = snapshotOf(lineup, long, short)
            assertEquals(1, days.airingNowhere(lineup, scheduled, snapshots), "${first.name} first")
        }
    }
}
