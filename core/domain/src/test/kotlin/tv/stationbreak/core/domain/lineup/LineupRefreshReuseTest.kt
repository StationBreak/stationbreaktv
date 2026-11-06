// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.domain.lineup.CuratedLibrary.boundaryMs
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.LineupPlanner
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * #589: a day built for one lineup is taken over by the next when everything that decides it is the same (its
 * [LineupDays.DayInput] and the library), so the build after a catalog swap works out only the days the swap moves.
 * The days taken over must be exactly the days a full build gives.
 */
class LineupRefreshReuseTest {
    private val hour = 3_600_000L
    private val now = NOON.toEpochMilliseconds() + 2 * hour
    private val noNames = CollectionNames(emptyMap())

    // Catalog 2 as a ranked catalog (its recipes are not programmed); catalog 3 selects by identity, programs every
    // recipe, and adds a channel.
    private val v2 =
        object : StarterCatalogView by TestCatalog {
            override val version: Int = 2
        }
    private val v3 =
        programmedCatalog(
            3,
            selects = true,
            extra =
                listOf(
                    Recipe(
                        "crime-too",
                        "Crime Too",
                        "New in catalog 3.",
                        Band.Genre,
                        330,
                        optIn = false,
                        sources = listOf(Source.Genre("Crime"), Source.Genre("Comedy")),
                        exclusions = emptyList(),
                        maxRating = null,
                        ordering = OrderingMode.Curated,
                        collectionHints = emptyList(),
                    ),
                ),
        )

    private fun plan(
        lineup: Lineup?,
        atMs: Long,
        catalog: StarterCatalogView,
    ): Lineup =
        LineupPlanner.plan(
            lineup,
            SERVER,
            USER,
            0,
            CuratedLibrary.snapshotAt(atMs),
            noNames,
            atMs,
            boundaryMs,
            catalog,
        )

    // The lineup of an installed ranked catalog: starters with rules that are not programmed, a custom channel and
    // a leftovers.
    private fun rankedLineup(): Lineup =
        lineupOf(
            starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
            starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Crime"))),
            starter("westerns", 220, ChannelState.On, rules("Westerns", Source.Genre("Western"))),
            channel("custom-1", 600, ChannelState.On, ChannelSource.Custom(rules("Mine", Source.Genre("Horror")))),
            leftovers("odds-and-ends", 199, null),
        )

    /** The days of [lineup], taking over from [earlier] what its inputs allow; how many were built and taken over. */
    private class Outcome(
        val days: List<ChannelDay>,
        val evaluations: Int,
        val reused: Set<ChannelId>,
    )

    private fun build(
        catalog: StarterCatalogView,
        lineup: Lineup,
        from: Map<ChannelId, Pair<LineupDays.DayInput, ChannelDay>> = emptyMap(),
        library: (Long) -> RuleSnapshot = CuratedLibrary::snapshotAt,
        atMs: Long = now,
    ): Pair<Outcome, Map<ChannelId, Pair<LineupDays.DayInput, ChannelDay>>> {
        val days = LineupDays(catalog)
        val scheduled = days.scheduled(lineup, atMs, null)
        val inputs = days.dayInputsOf(lineup, scheduled, atMs)
        val reused = HashMap<ChannelId, ChannelDay>()
        for (s in scheduled) {
            val before = from[s.channel.id]
            if (before != null && before.first == inputs[s.channel.id]) reused[s.channel.id] = before.second
        }
        val snapshots = scheduled.map { it.cutoffMs }.distinct().associateWith(library)
        var evaluations = 0
        val built = days.days(lineup, scheduled, snapshots, reused = reused, evaluated = { evaluations++ })
        val made = built.associate { it.channel.id to (inputs.getValue(it.channel.id) to it) }
        return Outcome(built, evaluations, reused.keys) to made
    }

    @Test
    fun theBuildAfterACatalogSwapWorksOutOnlyTheChannelsTheSwapMoves() {
        val first = plan(rankedLineup(), now - hour, v2)
        val swapped = plan(first, now, v3)
        val (_, served) = build(v2, first)

        val (reusing, _) = build(v3, swapped, from = served)
        val (full, _) = build(v3, swapped)

        // Preconditions: the swap added a channel and moved the plan, and there are days to take over.
        assertNotEquals(first, swapped)
        val added = LineupIds.starter("crime-too")
        assertTrue(swapped.channels.any { it.id == added }, "catalog 3's new channel")
        assertTrue(first.channels.none { it.id == added })
        assertTrue(reusing.reused.isNotEmpty(), "days were taken over")
        assertTrue(added !in reusing.reused, "the new channel is built")
        // Every channel that was on the air is taken over, but for the leftovers' (they read the new catalog's).
        val onAir = first.channels.filter { it.state == ChannelState.On && it.source !is ChannelSource.Leftovers }
        assertTrue(onAir.isNotEmpty())
        for (channel in onAir) assertTrue(channel.id in reusing.reused, "taken over: ${channel.id}")

        // The same days as a full build, with less work.
        assertEquals(full.days.map { it.channel.id }, reusing.days.map { it.channel.id })
        for (day in full.days) {
            assertEquals(day.aired(), reusing.days.dayOf(day.channel.id.value).aired(), "${day.channel.id}")
        }
        assertTrue(reusing.evaluations < full.evaluations, "${reusing.evaluations} < ${full.evaluations}")
    }

    // The edited lineup of a channel whose rules for today change: its own day moves, and the leftovers' (they claim
    // what the channel airs). Nothing else does.
    private fun withCustomRules(
        lineup: Lineup,
        genre: String,
    ): Lineup =
        lineup.copy(
            channels =
                lineup.channels.map {
                    if (it.id.value == "custom-1") {
                        it.copy(source = ChannelSource.Custom(rules("Mine", Source.Genre(genre))))
                    } else {
                        it
                    }
                },
        )

    @Test
    fun aChannelWhoseRulesForTodayChangedIsBuiltAndSoAreTheLeftoversThatClaimFromIt() {
        val first = plan(rankedLineup(), now - hour, v2)
        val (_, served) = build(v2, first)
        val edited = withCustomRules(first, "Documentary")

        val (reusing, _) = build(v2, edited, from = served)
        val (full, _) = build(v2, edited)

        val custom = ChannelId("custom-1")
        assertTrue(custom !in reusing.reused, "the edited channel is built")
        assertTrue(reusing.reused.none { it.value.startsWith("leftovers:") }, "the leftovers claim what it airs: built")
        assertEquals(
            setOf("laughs", "whodunit", "westerns").map { LineupIds.starter(it) }.toSet(),
            reusing.reused,
            "every other channel is taken over",
        )
        for (day in full.days) {
            assertEquals(day.aired(), reusing.days.dayOf(day.channel.id.value).aired(), "${day.channel.id}")
        }
        assertNotEquals(
            served.getValue(custom).second.aired(),
            reusing.days.dayOf("custom-1").aired(),
            "the edit really moved the day",
        )
    }
}
