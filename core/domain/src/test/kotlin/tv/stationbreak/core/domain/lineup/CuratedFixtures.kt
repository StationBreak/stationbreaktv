// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.Source

// Curated days (spec 23): starters whose rules are programmed (the curated order), over a library where titles fit
// more than one channel. Made-up IDs and genres only.

/** Rules in the curated order (rules format 3). */
internal fun programmed(
    name: String,
    vararg sources: Source,
): ChannelRules = rules(name, *sources).copy(formatVersion = 3, ordering = Ordering(OrderingMode.Curated))

/** The test catalog as [version], every recipe in the curated order; [change] edits a recipe, [extra] adds some. */
internal fun programmedCatalog(
    version: Int,
    selects: Boolean = false,
    change: (Recipe) -> Recipe = { it },
    extra: List<Recipe> = emptyList(),
): StarterCatalogView =
    object : StarterCatalogView by TestCatalog {
        override val version: Int = version
        override val selectsByIdentity: Boolean = selects
        override val recipes: List<Recipe> =
            (TestCatalog.recipes + extra).map { change(it.copy(ordering = OrderingMode.Curated)) }
    }

internal object CuratedLibrary {
    const val HALF_HOUR_MS = 1_800_000L
    const val EPISODES = 30

    /** 2026-10-19T06:00:00Z: today's day start; the next is a day later. */
    val todayStartMs: Long = NOON.toEpochMilliseconds() - 6 * 3_600_000L
    const val DAY_MS = 86_400_000L
    val boundaryMs: Long = todayStartMs + DAY_MS

    val comedy: List<RuleItem> = (0 until 8).map { movie(1000 + it, "Comedy", rating = "R") }
    val crime: List<RuleItem> = (0 until 8).map { movie(1100 + it, "Crime", rating = "R") }
    val western: List<RuleItem> = (0 until 8).map { movie(1200 + it, "Western", rating = "R") }

    /** Films that fit the comedy and the crime channel: one of them homes each. */
    val comedyCrime: List<RuleItem> = (0 until 4).map { movie(1300 + it, "Comedy", "Crime", rating = "R") }

    /** Films that fit the comedy and the western channel. */
    val comedyWestern: List<RuleItem> = (0 until 2).map { movie(1310 + it, "Comedy", "Western", rating = "R") }

    /** Films the custom channel airs. */
    val horror: List<RuleItem> = (0 until 3).map { movie(1400 + it, "Horror", rating = "R") }

    /** Films no channel of the lineups below airs: the leftovers' own. */
    val documentary: List<RuleItem> = (0 until 3).map { movie(1410 + it, "Documentary", rating = "R") }

    /** Show A: a long comedy series, over the share cap of the comedy channel, so it is trimmed. */
    val showA: CanonicalId = id(1500)
    val showAEpisodes: List<RuleItem> =
        (1..EPISODES).map {
            RuleItem(
                ScheduleItem(
                    id(1500 + it),
                    ScheduleItemKind.Episode,
                    HALF_HOUR_MS,
                    ADDED_MS,
                    null,
                    null,
                    2001,
                    showA,
                    1,
                    it,
                ),
                LIBRARY_ID,
                null,
                "TV-14",
                listOf("Comedy"),
                emptyList(),
            )
        }

    val items: List<RuleItem> =
        (comedy + crime + western + comedyCrime + comedyWestern + horror + documentary + showAEpisodes)
            .sortedBy { it.item.id.value }

    fun ids(entries: List<RuleItem>): Set<CanonicalId> = entries.mapTo(HashSet()) { it.item.id }

    fun snapshotAt(cutoffMs: Long): RuleSnapshot =
        RuleSnapshot(
            cutoffMs,
            setOf(LIBRARY_ID),
            items,
            mapOf(showA to RuleSeries(showA, "TV-14", listOf("Comedy"), emptyList())),
            emptyMap(),
            collectionsSynced = true,
        )

    val laughs: ChannelRules = programmed("Laughs", Source.Genre("Comedy"))
    val whodunit: ChannelRules = programmed("Whodunit", Source.Genre("Crime"))
    val westerns: ChannelRules = programmed("Westerns", Source.Genre("Western"))

    val laughsId = LineupIds.starter("laughs")
    val whodunitId = LineupIds.starter("whodunit")
    val westernsId = LineupIds.starter("westerns")

    /** Three programmed starters on, a custom channel of horror, and the grown-up leftovers. */
    fun lineup(
        westernsState: ChannelState = ChannelState.On,
        whodunitState: ChannelState = ChannelState.On,
    ): Lineup =
        lineupOf(
            starter("laughs", 110, ChannelState.On, laughs),
            starter("whodunit", 210, whodunitState, whodunit),
            starter("westerns", 220, westernsState, westerns),
            channel("custom-1", 600, ChannelState.On, ChannelSource.Custom(rules("Mine", Source.Genre("Horror")))),
            leftovers("odds-and-ends", 199, null),
        )

    fun Lineup.channel(id: String): LineupChannel = channels.first { it.id.value == id }
}

/** The days of [lineup] at [atMs] over the curated library. */
internal fun LineupDays.curatedDays(
    lineup: Lineup,
    atMs: Long,
): List<ChannelDay> {
    val scheduled = scheduled(lineup, atMs, null)
    val snapshots = scheduled.map { it.cutoffMs }.distinct().associateWith { CuratedLibrary.snapshotAt(it) }
    return days(lineup, scheduled, snapshots)
}

internal fun List<ChannelDay>.dayOf(id: String): ChannelDay = single { it.channel.id.value == id }

internal fun ChannelDay.ids(): Set<CanonicalId> = pool.mapTo(HashSet()) { it.id }

/** What a day airs and how: its pool, its weights and its blocks. */
internal fun ChannelDay.aired(): List<Any?> = listOf(pool, weights.entries, films.entries, hidden, day.blocks)
